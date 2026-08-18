/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.settings.SecureStorageUtil;
import com.codepilot1c.core.settings.SecureStorageUtil.ApiKeyReadResult;
import com.codepilot1c.core.settings.VibePreferenceConstants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * Service for storing and loading LLM provider configurations.
 *
 * <p>Non-secret configuration is stored as JSON in Eclipse preferences. Since config version 2,
 * provider API keys are stored separately in Eclipse Secure Storage. Downgrading to an older
 * plugin can therefore require re-entering a key; the secure-storage copy is retained.</p>
 */
public class LlmProviderConfigStore {

    static final int CURRENT_CONFIG_VERSION = 2;
    private static final String MIGRATION_WARNING =
            "Provider API keys could not be migrated to secure storage; plaintext preferences were preserved for retry."; //$NON-NLS-1$
    private static final String SAVE_WARNING =
            "Provider preferences were not saved because an API key could not be stored securely."; //$NON-NLS-1$
    private static final String DELETE_WARNING =
            "Provider was not deleted because its secure API key could not be removed."; //$NON-NLS-1$
    private static final String READ_WARNING =
            "Provider preferences were not saved because the previous API key could not be read securely."; //$NON-NLS-1$
    static final String RESERVED_BACKEND_PROVIDER_ID = "backend"; //$NON-NLS-1$
    private static LlmProviderConfigStore instance;

    /**
     * Listener notified when provider configs are persisted.
     */
    public interface ProviderConfigChangeListener {
        void onProviderConfigsChanged();
    }

    private final Gson gson;
    private final PreferenceAccess preferences;
    private final ApiKeyStorage apiKeyStorage;
    private final WarningSink warningSink;
    private List<LlmProviderConfig> cachedConfigs;
    private String cachedActiveProviderId;
    private Map<String, CredentialBinding> credentialBindings = new LinkedHashMap<>();
    private Set<String> unsecuredPlaintextProviderIds = new HashSet<>();
    private final List<ProviderConfigChangeListener> listeners = new CopyOnWriteArrayList<>();
    private boolean migrationWarningLogged;

    private LlmProviderConfigStore() {
        this(new EclipsePreferenceAccess(), new EclipseApiKeyStorage(), VibeCorePlugin::logWarn);
    }

    LlmProviderConfigStore(PreferenceAccess preferences, ApiKeyStorage apiKeyStorage, WarningSink warningSink) {
        this.preferences = preferences;
        this.apiKeyStorage = apiKeyStorage;
        this.warningSink = warningSink;
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .create();
    }

    /**
     * Returns the singleton instance.
     */
    public static synchronized LlmProviderConfigStore getInstance() {
        if (instance == null) {
            instance = new LlmProviderConfigStore();
        }
        return instance;
    }

    /**
     * Adds a listener notified when configs are persisted.
     */
    public void addListener(ProviderConfigChangeListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Removes a previously added listener.
     */
    public void removeListener(ProviderConfigChangeListener listener) {
        listeners.remove(listener);
    }

    /**
     * Returns all configured providers.
     */
    public List<LlmProviderConfig> getProviders() {
        if (cachedConfigs == null) {
            loadFromPreferences();
        }
        return Collections.unmodifiableList(cachedConfigs);
    }

    /**
     * Returns a provider by its ID.
     */
    public Optional<LlmProviderConfig> getProvider(String id) {
        return getProviders().stream()
                .filter(p -> p.getId().equals(id))
                .findFirst();
    }

    /**
     * Returns the active provider configuration.
     */
    public Optional<LlmProviderConfig> getActiveProvider() {
        String activeId = getActiveProviderId();
        if (activeId == null || activeId.isEmpty()) {
            // Return first configured provider if no active set
            return getProviders().stream()
                    .filter(LlmProviderConfig::isConfigured)
                    .findFirst();
        }
        return getProvider(activeId);
    }

    /**
     * Returns the active provider ID.
     */
    public String getActiveProviderId() {
        if (cachedActiveProviderId == null) {
            loadFromPreferences();
        }
        return cachedActiveProviderId;
    }

    /**
     * Sets the active provider by ID.
     *
     * @return {@code true} when preferences were persisted
     */
    public boolean setActiveProviderId(String id) {
        if (cachedConfigs == null) {
            loadFromPreferences();
        }
        return persistCandidate(cachedConfigs, id);
    }

    /**
     * Adds a new provider configuration.
     *
     * @return {@code true} when the provider was persisted
     */
    public boolean addProvider(LlmProviderConfig config) {
        if (config == null || isReservedId(config.getId())) {
            VibeCorePlugin.logWarn("Ignoring provider config with reserved ID: " //$NON-NLS-1$
                    + (config != null ? config.getId() : "null")); //$NON-NLS-1$
            return false;
        }
        if (cachedConfigs == null) {
            loadFromPreferences();
        }
        List<LlmProviderConfig> candidate = copies(cachedConfigs);
        candidate.add(config.copy());
        return persistCandidate(candidate, cachedActiveProviderId);
    }

    /**
     * Updates an existing provider configuration.
     *
     * @return {@code true} when the provider was persisted
     */
    public boolean updateProvider(LlmProviderConfig config) {
        if (config == null || isReservedId(config.getId())) {
            VibeCorePlugin.logWarn("Ignoring update for provider config with reserved ID: " //$NON-NLS-1$
                    + (config != null ? config.getId() : "null")); //$NON-NLS-1$
            return false;
        }
        if (cachedConfigs == null) {
            loadFromPreferences();
        }
        List<LlmProviderConfig> candidate = copies(cachedConfigs);
        for (int i = 0; i < candidate.size(); i++) {
            if (candidate.get(i).getId().equals(config.getId())) {
                candidate.set(i, config.copy());
                break;
            }
        }
        return persistCandidate(candidate, cachedActiveProviderId);
    }

    /**
     * Removes a provider by ID.
     *
     * @return {@code true} when the provider was removed and preferences were persisted
     */
    public boolean removeProvider(String id) {
        if (cachedConfigs == null) {
            loadFromPreferences();
        }
        List<LlmProviderConfig> candidate = copies(cachedConfigs);
        candidate.removeIf(p -> Objects.equals(p.getId(), id));
        // Clear active if it was the removed provider
        String candidateActiveId = cachedActiveProviderId;
        if (id != null && id.equals(cachedActiveProviderId)) {
            candidateActiveId = null;
        }
        return persistCandidate(candidate, candidateActiveId);
    }

    /**
     * Saves all providers at once (for batch updates).
     *
     * @return {@code true} when providers were persisted
     */
    public boolean saveProviders(List<LlmProviderConfig> providers) {
        return saveProviders(providers, cachedActiveProviderId);
    }

    /**
     * Saves providers and their active selection in one compensating transaction.
     *
     * @param providers provider configurations
     * @param activeProviderId active provider ID, or {@code null} to clear it
     * @return {@code true} when providers and the active selection were persisted
     */
    public boolean saveProviders(List<LlmProviderConfig> providers, String activeProviderId) {
        LoadedState sanitized = sanitizeLoadedState(
                providers != null ? providers : List.of(),
                activeProviderId);
        return persistCandidate(sanitized.configs(), sanitized.activeProviderId());
    }

    /**
     * Clears the cache and reloads from preferences.
     */
    public void refresh() {
        cachedConfigs = null;
        cachedActiveProviderId = null;
        credentialBindings = new LinkedHashMap<>();
        unsecuredPlaintextProviderIds = new HashSet<>();
        loadFromPreferences();
    }

    /**
     * Loads configurations from Eclipse preferences.
     */
    private void loadFromPreferences() {
        // Load providers JSON
        String json = preferences.get(VibePreferenceConstants.PREF_LLM_PROVIDERS, "[]"); //$NON-NLS-1$
        try {
            List<LlmProviderConfig> loadedConfigs = new ArrayList<>();
            JsonArray array = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement element : array) {
                LlmProviderConfig config = gson.fromJson(element, LlmProviderConfig.class);
                if (config != null) {
                    loadedConfigs.add(config);
                }
            }
            LoadedState sanitized = sanitizeLoadedState(
                    loadedConfigs,
                    preferences.get(VibePreferenceConstants.PREF_LLM_ACTIVE_PROVIDER_ID, "")); //$NON-NLS-1$
            loadedConfigs = sanitized.configs();
            cachedActiveProviderId = sanitized.activeProviderId();

            int storedVersion = preferences.getInt(VibePreferenceConstants.PREF_LLM_CONFIG_VERSION, 0);
            Set<String> plaintextProviderIds = plaintextProviderIds(loadedConfigs);
            MigrationResult migration = resolveAndMigrateKeys(loadedConfigs, storedVersion < CURRENT_CONFIG_VERSION);
            if (storedVersion < CURRENT_CONFIG_VERSION) {
                if (migration.allPlaintextSecured()) {
                    persistState(loadedConfigs, cachedActiveProviderId, CURRENT_CONFIG_VERSION);
                } else {
                    warnMigrationOnce();
                }
            } else if (!migration.securedPlaintextConfigs().isEmpty()
                    && migration.unsecuredPlaintextConfigs().isEmpty()) {
                persistState(loadedConfigs, cachedActiveProviderId, storedVersion);
            }
            applyResolvedKeys(loadedConfigs, migration.resolvedKeys());
            cachedConfigs = loadedConfigs;
            unsecuredPlaintextProviderIds = migration.unsecuredPlaintextConfigs().isEmpty()
                    ? new HashSet<>() : plaintextProviderIds;
            rememberBindings(loadedConfigs);
        } catch (Exception e) {
            warningSink.warn("Failed to parse provider configs; using an empty configuration."); //$NON-NLS-1$
            cachedConfigs = new ArrayList<>();
            cachedActiveProviderId = preferences.get(VibePreferenceConstants.PREF_LLM_ACTIVE_PROVIDER_ID, ""); //$NON-NLS-1$
            credentialBindings = new LinkedHashMap<>();
            unsecuredPlaintextProviderIds = new HashSet<>();
        }
    }

    /** Persists a secret-free provider snapshot. */
    private boolean persistState(List<LlmProviderConfig> configs, String activeProviderId, int version) {
        try {
            preferences.put(VibePreferenceConstants.PREF_LLM_PROVIDERS,
                    gson.toJson(serializableCopies(configs)));
            preferences.put(VibePreferenceConstants.PREF_LLM_ACTIVE_PROVIDER_ID,
                    activeProviderId != null ? activeProviderId : ""); //$NON-NLS-1$
            preferences.putInt(VibePreferenceConstants.PREF_LLM_CONFIG_VERSION, version);
            preferences.flush();
            return true;
        } catch (BackingStoreException e) {
            warningSink.warn("Failed to persist provider preferences."); //$NON-NLS-1$
            return false;
        }
    }

    private List<LlmProviderConfig> serializableCopies(List<LlmProviderConfig> configs) {
        List<LlmProviderConfig> copies = new ArrayList<>();
        for (LlmProviderConfig config : configs) {
            LlmProviderConfig copy = config.copy();
            copy.setApiKey(null);
            copies.add(copy);
        }
        return copies;
    }

    /**
     * Persists credentials and non-secret preferences as one compensating transaction.
     * A null incoming key means unchanged; an empty key means an explicit clear.
     */
    private boolean persistCandidate(List<LlmProviderConfig> candidateConfigs, String candidateActiveId) {
        List<LlmProviderConfig> candidate = copies(candidateConfigs);
        Map<String, LlmProviderConfig> currentById = byId(cachedConfigs);
        List<KeyMutation> mutations = new ArrayList<>();
        Set<String> candidateIds = new HashSet<>();

        for (LlmProviderConfig config : candidate) {
            candidateIds.add(config.getId());
            LlmProviderConfig current = currentById.get(config.getId());
            ApiKeyReadResult beforeRead = safeRetrieveApiKey(config.getId());
            String before = beforeRead.value();
            String oldEffective = beforeRead.isPresent() ? before
                    : current != null && current.getApiKey() != null ? current.getApiKey() : ""; //$NON-NLS-1$
            CredentialBinding binding = credentialBindings.get(config.getId());
            boolean transitioned = binding != null && !binding.matches(config);
            String incoming = config.getApiKey();
            boolean credentialChangeRequested = current == null
                    || transitioned
                    || incoming != null && !Objects.equals(incoming, current.getApiKey())
                    || unsecuredPlaintextProviderIds.contains(config.getId());
            if (beforeRead.isReadFailed() && credentialChangeRequested) {
                warningSink.warn(READ_WARNING);
                return false;
            }
            String desired;
            if (config.getType() == null || !config.getType().requiresStaticApiKey()) {
                desired = ""; //$NON-NLS-1$
            } else if (current == null) {
                desired = incoming != null ? incoming : ""; //$NON-NLS-1$
            } else if (transitioned) {
                desired = incoming != null && !incoming.isEmpty() && !incoming.equals(oldEffective)
                        ? incoming : ""; //$NON-NLS-1$
            } else {
                desired = incoming == null ? oldEffective : incoming;
            }
            config.setApiKey(desired);
            if (!beforeRead.isReadFailed() && !before.equals(desired)) {
                mutations.add(new KeyMutation(config.getId(), before, desired));
            }
        }
        for (LlmProviderConfig current : currentById.values()) {
            if (!candidateIds.contains(current.getId())) {
                ApiKeyReadResult beforeRead = safeRetrieveApiKey(current.getId());
                if (beforeRead.isReadFailed()) {
                    warningSink.warn(READ_WARNING);
                    return false;
                }
                String before = beforeRead.value();
                if (!before.isEmpty()) {
                    mutations.add(new KeyMutation(current.getId(), before, "")); //$NON-NLS-1$
                }
            }
        }

        List<KeyMutation> applied = new ArrayList<>();
        for (KeyMutation mutation : mutations) {
            if (!applyMutation(mutation)) {
                rollbackMutations(applied);
                warningSink.warn(mutation.after().isEmpty() ? DELETE_WARNING : SAVE_WARNING);
                return false;
            }
            applied.add(mutation);
        }

        PreferenceSnapshot previousPreferences = preferenceSnapshot();
        if (!persistState(candidate, candidateActiveId, CURRENT_CONFIG_VERSION)) {
            rollbackMutations(applied);
            restorePreferences(previousPreferences);
            return false;
        }
        cachedConfigs = candidate;
        cachedActiveProviderId = candidateActiveId != null ? candidateActiveId : ""; //$NON-NLS-1$
        unsecuredPlaintextProviderIds = new HashSet<>();
        rememberBindings(candidate);
        notifyListeners();
        return true;
    }

    private boolean applyMutation(KeyMutation mutation) {
        return mutation.after().isEmpty()
                ? safeRemoveApiKey(mutation.providerId())
                : safeStoreApiKey(mutation.providerId(), mutation.after());
    }

    private void rollbackMutations(List<KeyMutation> applied) {
        for (int i = applied.size() - 1; i >= 0; i--) {
            KeyMutation mutation = applied.get(i);
            if (mutation.before().isEmpty()) {
                safeRemoveApiKey(mutation.providerId());
            } else {
                safeStoreApiKey(mutation.providerId(), mutation.before());
            }
        }
    }

    private PreferenceSnapshot preferenceSnapshot() {
        return new PreferenceSnapshot(
                preferences.get(VibePreferenceConstants.PREF_LLM_PROVIDERS, "[]"), //$NON-NLS-1$
                preferences.get(VibePreferenceConstants.PREF_LLM_ACTIVE_PROVIDER_ID, ""), //$NON-NLS-1$
                preferences.getInt(VibePreferenceConstants.PREF_LLM_CONFIG_VERSION, 0));
    }

    private void restorePreferences(PreferenceSnapshot snapshot) {
        try {
            preferences.put(VibePreferenceConstants.PREF_LLM_PROVIDERS, snapshot.providersJson());
            preferences.put(VibePreferenceConstants.PREF_LLM_ACTIVE_PROVIDER_ID, snapshot.activeProviderId());
            preferences.putInt(VibePreferenceConstants.PREF_LLM_CONFIG_VERSION, snapshot.version());
            preferences.flush();
        } catch (BackingStoreException ignored) {
            // The original persistence warning is already body-safe and sufficient.
        }
    }

    private void rememberBindings(List<LlmProviderConfig> configs) {
        Map<String, CredentialBinding> bindings = new LinkedHashMap<>();
        for (LlmProviderConfig config : configs) {
            bindings.put(config.getId(), CredentialBinding.of(config));
        }
        credentialBindings = bindings;
    }

    private static Map<String, LlmProviderConfig> byId(List<LlmProviderConfig> configs) {
        Map<String, LlmProviderConfig> result = new LinkedHashMap<>();
        if (configs != null) {
            for (LlmProviderConfig config : configs) {
                if (config != null) result.put(config.getId(), config);
            }
        }
        return result;
    }

    private static List<LlmProviderConfig> copies(List<LlmProviderConfig> configs) {
        List<LlmProviderConfig> result = new ArrayList<>();
        if (configs != null) {
            for (LlmProviderConfig config : configs) {
                if (config != null) result.add(config.copy());
            }
        }
        return result;
    }

    private static Set<String> plaintextProviderIds(List<LlmProviderConfig> configs) {
        Set<String> result = new HashSet<>();
        for (LlmProviderConfig config : configs) {
            if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
                result.add(config.getId());
            }
        }
        return result;
    }

    private void notifyListeners() {
        for (ProviderConfigChangeListener listener : listeners) {
            try {
                listener.onProviderConfigsChanged();
            } catch (Exception e) {
                warningSink.warn("Provider config listener failed."); //$NON-NLS-1$
            }
        }
    }

    private MigrationResult resolveAndMigrateKeys(List<LlmProviderConfig> configs, boolean forceLegacyWrites) {
        Map<LlmProviderConfig, String> resolved = new IdentityHashMap<>();
        Set<LlmProviderConfig> securedPlaintext = identitySet();
        Set<LlmProviderConfig> unsecuredPlaintext = identitySet();
        boolean allPlaintextSecured = true;

        for (LlmProviderConfig config : configs) {
            String plaintext = config.getApiKey();
            boolean hasPlaintext = plaintext != null && !plaintext.isEmpty();
            ApiKeyReadResult secureRead;
            String secureKey = ""; //$NON-NLS-1$

            if (config.getType() == null || !config.getType().requiresStaticApiKey()) {
                secureRead = safeRetrieveApiKey(config.getId());
                boolean removed = !secureRead.isReadFailed()
                        && (!secureRead.isPresent() || safeRemoveApiKey(config.getId()));
                if (hasPlaintext) {
                    if (removed) securedPlaintext.add(config);
                    else unsecuredPlaintext.add(config);
                }
                if (!removed) {
                    allPlaintextSecured = false;
                    warnMigrationOnce();
                }
                if (secureRead.isReadFailed()) {
                    allPlaintextSecured = false;
                }
                resolved.put(config, ""); //$NON-NLS-1$
                continue;
            }

            if (hasPlaintext && forceLegacyWrites) {
                if (safeStoreApiKey(config.getId(), plaintext)) {
                    securedPlaintext.add(config);
                    secureKey = plaintext;
                } else {
                    allPlaintextSecured = false;
                    unsecuredPlaintext.add(config);
                    secureRead = safeRetrieveApiKey(config.getId());
                    secureKey = secureRead.isPresent() ? secureRead.value() : ""; //$NON-NLS-1$
                }
            } else {
                secureRead = safeRetrieveApiKey(config.getId());
                secureKey = secureRead.isPresent() ? secureRead.value() : ""; //$NON-NLS-1$
                if (hasPlaintext) {
                    if (secureRead.isPresent()
                            || !secureRead.isReadFailed() && safeStoreApiKey(config.getId(), plaintext)) {
                        securedPlaintext.add(config);
                        if (secureKey.isEmpty()) {
                            secureKey = plaintext;
                        }
                    } else {
                        allPlaintextSecured = false;
                        unsecuredPlaintext.add(config);
                        warnMigrationOnce();
                    }
                } else if (forceLegacyWrites && secureRead.isReadFailed()) {
                    allPlaintextSecured = false;
                    warnMigrationOnce();
                }
            }

            resolved.put(config, !secureKey.isEmpty() ? secureKey : plaintext);
        }
        return new MigrationResult(resolved, securedPlaintext, unsecuredPlaintext, allPlaintextSecured);
    }

    private void applyResolvedKeys(List<LlmProviderConfig> configs, Map<LlmProviderConfig, String> resolvedKeys) {
        for (LlmProviderConfig config : configs) {
            config.setApiKey(resolvedKeys.get(config));
        }
    }

    private ApiKeyReadResult safeRetrieveApiKey(String providerId) {
        if (providerId == null || providerId.isEmpty()) {
            return ApiKeyReadResult.absent();
        }
        try {
            ApiKeyReadResult result = apiKeyStorage.retrieveApiKey(providerId);
            return result != null ? result : ApiKeyReadResult.readFailed();
        } catch (RuntimeException e) {
            return ApiKeyReadResult.readFailed();
        }
    }

    private boolean safeStoreApiKey(String providerId, String apiKey) {
        if (providerId == null || providerId.isEmpty()) {
            return false;
        }
        try {
            return apiKeyStorage.storeApiKey(providerId, apiKey);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean safeRemoveApiKey(String providerId) {
        try {
            return apiKeyStorage.removeApiKey(providerId);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void warnMigrationOnce() {
        if (!migrationWarningLogged) {
            migrationWarningLogged = true;
            warningSink.warn(MIGRATION_WARNING);
        }
    }

    private static Set<LlmProviderConfig> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    static LoadedState sanitizeLoadedState(List<LlmProviderConfig> configs, String activeProviderId) {
        List<LlmProviderConfig> sanitizedConfigs = new ArrayList<>();
        boolean strippedReservedConfig = false;
        if (configs != null) {
            for (LlmProviderConfig config : configs) {
                if (config == null) {
                    continue;
                }
                if (isReservedId(config.getId())) {
                    strippedReservedConfig = true;
                    VibeCorePlugin.logWarn("Skipping persisted config with reserved system ID: " + config.getId()); //$NON-NLS-1$
                    continue;
                }
                sanitizedConfigs.add(config);
            }
        }

        String sanitizedActiveProviderId = activeProviderId != null ? activeProviderId : ""; //$NON-NLS-1$
        if (strippedReservedConfig && isReservedId(sanitizedActiveProviderId)) {
            sanitizedActiveProviderId = ""; //$NON-NLS-1$
        }

        return new LoadedState(sanitizedConfigs, sanitizedActiveProviderId);
    }

    static boolean isReservedId(String id) {
        return RESERVED_BACKEND_PROVIDER_ID.equals(id);
    }

    /**
     * Resolves a provider key for request-time use. Secure storage wins over the in-memory JSON
     * fallback; when only the fallback exists, it is copied to secure storage opportunistically.
     */
    static String resolveApiKey(LlmProviderConfig config) {
        return resolveApiKey(config, new EclipseApiKeyStorage());
    }

    static String resolveApiKey(LlmProviderConfig config, ApiKeyStorage storage) {
        if (config == null) {
            return ""; //$NON-NLS-1$
        }
        if (config.getType() == null || !config.getType().requiresStaticApiKey()) {
            return ""; //$NON-NLS-1$
        }
        String providerId = config.getId();
        String plaintext = config.getApiKey();
        ApiKeyReadResult secureRead = ApiKeyReadResult.absent();
        if (providerId != null && !providerId.isEmpty()) {
            try {
                ApiKeyReadResult stored = storage.retrieveApiKey(providerId);
                secureRead = stored != null ? stored : ApiKeyReadResult.readFailed();
            } catch (RuntimeException e) {
                secureRead = ApiKeyReadResult.readFailed();
            }
        }
        if (secureRead.isPresent()) {
            return secureRead.value();
        }
        if (!secureRead.isReadFailed()
                && plaintext != null && !plaintext.isEmpty()
                && providerId != null && !providerId.isEmpty()) {
            try {
                storage.storeApiKey(providerId, plaintext);
            } catch (RuntimeException e) {
                // Request-time fallback remains usable and the next config load will retry.
            }
        }
        return plaintext != null ? plaintext : ""; //$NON-NLS-1$
    }

    interface PreferenceAccess {
        String get(String key, String defaultValue);

        int getInt(String key, int defaultValue);

        void put(String key, String value);

        void putInt(String key, int value);

        void flush() throws BackingStoreException;
    }

    interface ApiKeyStorage {
        boolean storeApiKey(String providerId, String apiKey);

        ApiKeyReadResult retrieveApiKey(String providerId);

        boolean removeApiKey(String providerId);
    }

    interface WarningSink {
        void warn(String message);
    }

    private static final class EclipsePreferenceAccess implements PreferenceAccess {
        private final IEclipsePreferences delegate =
                InstanceScope.INSTANCE.getNode(VibeCorePlugin.PLUGIN_ID);

        @Override
        public String get(String key, String defaultValue) {
            return delegate.get(key, defaultValue);
        }

        @Override
        public int getInt(String key, int defaultValue) {
            return delegate.getInt(key, defaultValue);
        }

        @Override
        public void put(String key, String value) {
            delegate.put(key, value);
        }

        @Override
        public void putInt(String key, int value) {
            delegate.putInt(key, value);
        }

        @Override
        public void flush() throws BackingStoreException {
            delegate.flush();
        }
    }

    private static final class EclipseApiKeyStorage implements ApiKeyStorage {
        @Override
        public boolean storeApiKey(String providerId, String apiKey) {
            return SecureStorageUtil.storeApiKey(providerId, apiKey);
        }

        @Override
        public ApiKeyReadResult retrieveApiKey(String providerId) {
            return SecureStorageUtil.readApiKey(providerId);
        }

        @Override
        public boolean removeApiKey(String providerId) {
            return SecureStorageUtil.removeApiKey(providerId);
        }
    }

    private static final class MigrationResult {
        private final Map<LlmProviderConfig, String> resolvedKeys;
        private final Set<LlmProviderConfig> securedPlaintextConfigs;
        private final Set<LlmProviderConfig> unsecuredPlaintextConfigs;
        private final boolean allPlaintextSecured;

        MigrationResult(Map<LlmProviderConfig, String> resolvedKeys,
                Set<LlmProviderConfig> securedPlaintextConfigs,
                Set<LlmProviderConfig> unsecuredPlaintextConfigs,
                boolean allPlaintextSecured) {
            this.resolvedKeys = resolvedKeys;
            this.securedPlaintextConfigs = securedPlaintextConfigs;
            this.unsecuredPlaintextConfigs = unsecuredPlaintextConfigs;
            this.allPlaintextSecured = allPlaintextSecured;
        }

        Map<LlmProviderConfig, String> resolvedKeys() {
            return resolvedKeys;
        }

        Set<LlmProviderConfig> securedPlaintextConfigs() {
            return securedPlaintextConfigs;
        }

        Set<LlmProviderConfig> unsecuredPlaintextConfigs() {
            return unsecuredPlaintextConfigs;
        }

        boolean allPlaintextSecured() {
            return allPlaintextSecured;
        }
    }

    static final class LoadedState {
        private final List<LlmProviderConfig> configs;
        private final String activeProviderId;

        LoadedState(List<LlmProviderConfig> configs, String activeProviderId) {
            this.configs = new ArrayList<>(configs);
            this.activeProviderId = activeProviderId != null ? activeProviderId : ""; //$NON-NLS-1$
        }

        List<LlmProviderConfig> configs() {
            return new ArrayList<>(configs);
        }

        String activeProviderId() {
            return activeProviderId;
        }
    }

    private record CredentialBinding(ProviderType type, String baseUrl) {
        static CredentialBinding of(LlmProviderConfig config) {
            return new CredentialBinding(config.getType(), normalized(config.getBaseUrl()));
        }

        boolean matches(LlmProviderConfig config) {
            return type == config.getType() && Objects.equals(baseUrl, normalized(config.getBaseUrl()));
        }

        private static String normalized(String value) {
            return value == null ? "" : value.trim(); //$NON-NLS-1$
        }
    }

    private record KeyMutation(String providerId, String before, String after) { }

    private record PreferenceSnapshot(String providersJson, String activeProviderId, int version) { }

    /**
     * Checks if there are any configured providers.
     */
    public boolean hasConfiguredProviders() {
        return getProviders().stream().anyMatch(LlmProviderConfig::isConfigured);
    }

    /**
     * Returns the current config version stored in preferences.
     */
    public int getStoredConfigVersion() {
        return preferences.getInt(VibePreferenceConstants.PREF_LLM_CONFIG_VERSION, 0);
    }
}
