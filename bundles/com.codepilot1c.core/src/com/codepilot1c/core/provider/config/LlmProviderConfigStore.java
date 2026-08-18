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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.settings.SecureStorageUtil;
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
     */
    public void setActiveProviderId(String id) {
        this.cachedActiveProviderId = id;
        saveToPreferences();
    }

    /**
     * Adds a new provider configuration.
     */
    public void addProvider(LlmProviderConfig config) {
        if (config == null || isReservedId(config.getId())) {
            VibeCorePlugin.logWarn("Ignoring provider config with reserved ID: " //$NON-NLS-1$
                    + (config != null ? config.getId() : "null")); //$NON-NLS-1$
            return;
        }
        if (cachedConfigs == null) {
            loadFromPreferences();
        }
        cachedConfigs.add(config);
        saveToPreferences();
    }

    /**
     * Updates an existing provider configuration.
     */
    public void updateProvider(LlmProviderConfig config) {
        if (config == null || isReservedId(config.getId())) {
            VibeCorePlugin.logWarn("Ignoring update for provider config with reserved ID: " //$NON-NLS-1$
                    + (config != null ? config.getId() : "null")); //$NON-NLS-1$
            return;
        }
        if (cachedConfigs == null) {
            loadFromPreferences();
        }
        for (int i = 0; i < cachedConfigs.size(); i++) {
            if (cachedConfigs.get(i).getId().equals(config.getId())) {
                cachedConfigs.set(i, config);
                break;
            }
        }
        saveToPreferences();
    }

    /**
     * Removes a provider by ID.
     */
    public void removeProvider(String id) {
        if (cachedConfigs == null) {
            loadFromPreferences();
        }
        boolean exists = id != null && cachedConfigs.stream().anyMatch(p -> id.equals(p.getId()));
        if (exists && !safeRemoveApiKey(id)) {
            warningSink.warn(DELETE_WARNING);
            return;
        }
        cachedConfigs.removeIf(p -> p.getId().equals(id));
        // Clear active if it was the removed provider
        if (id != null && id.equals(cachedActiveProviderId)) {
            cachedActiveProviderId = null;
        }
        saveToPreferences();
    }

    /**
     * Saves all providers at once (for batch updates).
     */
    public void saveProviders(List<LlmProviderConfig> providers) {
        LoadedState sanitized = sanitizeLoadedState(
                providers != null ? providers : List.of(),
                cachedActiveProviderId);
        this.cachedConfigs = sanitized.configs();
        this.cachedActiveProviderId = sanitized.activeProviderId();
        saveToPreferences();
    }

    /**
     * Clears the cache and reloads from preferences.
     */
    public void refresh() {
        cachedConfigs = null;
        cachedActiveProviderId = null;
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
            MigrationResult migration = resolveAndMigrateKeys(loadedConfigs, storedVersion < CURRENT_CONFIG_VERSION);
            if (storedVersion < CURRENT_CONFIG_VERSION) {
                if (migration.allPlaintextSecured()) {
                    persistState(loadedConfigs, cachedActiveProviderId, CURRENT_CONFIG_VERSION, false);
                } else {
                    warnMigrationOnce();
                }
            } else if (!migration.securedPlaintextConfigs().isEmpty()
                    && migration.unsecuredPlaintextConfigs().isEmpty()) {
                persistState(loadedConfigs, cachedActiveProviderId, storedVersion, false);
            }
            applyResolvedKeys(loadedConfigs, migration.resolvedKeys());
            cachedConfigs = loadedConfigs;
        } catch (Exception e) {
            warningSink.warn("Failed to parse provider configs; using an empty configuration."); //$NON-NLS-1$
            cachedConfigs = new ArrayList<>();
            cachedActiveProviderId = preferences.get(VibePreferenceConstants.PREF_LLM_ACTIVE_PROVIDER_ID, ""); //$NON-NLS-1$
        }
    }

    /**
     * Saves configurations to Eclipse preferences.
     */
    private void saveToPreferences() {
        if (!secureKeysForSave(cachedConfigs)) {
            warningSink.warn(SAVE_WARNING);
            return;
        }
        persistState(cachedConfigs, cachedActiveProviderId, CURRENT_CONFIG_VERSION, true);
    }

    /** Persists a secret-free provider snapshot. */
    private boolean persistState(List<LlmProviderConfig> configs, String activeProviderId, int version,
            boolean notifyListeners) {
        try {
            preferences.put(VibePreferenceConstants.PREF_LLM_PROVIDERS,
                    gson.toJson(serializableCopies(configs)));
            preferences.put(VibePreferenceConstants.PREF_LLM_ACTIVE_PROVIDER_ID,
                    activeProviderId != null ? activeProviderId : ""); //$NON-NLS-1$
            preferences.putInt(VibePreferenceConstants.PREF_LLM_CONFIG_VERSION, version);
            preferences.flush();
            if (notifyListeners) {
                notifyListeners();
            }
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
            String secureKey = ""; //$NON-NLS-1$

            if (hasPlaintext && forceLegacyWrites) {
                if (safeStoreApiKey(config.getId(), plaintext)) {
                    securedPlaintext.add(config);
                    secureKey = plaintext;
                } else {
                    allPlaintextSecured = false;
                    unsecuredPlaintext.add(config);
                    secureKey = safeRetrieveApiKey(config.getId());
                }
            } else {
                secureKey = safeRetrieveApiKey(config.getId());
                if (hasPlaintext) {
                    if (!secureKey.isEmpty() || safeStoreApiKey(config.getId(), plaintext)) {
                        securedPlaintext.add(config);
                        if (secureKey.isEmpty()) {
                            secureKey = plaintext;
                        }
                    } else {
                        allPlaintextSecured = false;
                        unsecuredPlaintext.add(config);
                        warnMigrationOnce();
                    }
                }
            }

            resolved.put(config, !secureKey.isEmpty() ? secureKey : plaintext);
        }
        return new MigrationResult(resolved, securedPlaintext, unsecuredPlaintext, allPlaintextSecured);
    }

    private boolean secureKeysForSave(List<LlmProviderConfig> configs) {
        boolean success = true;
        for (LlmProviderConfig config : configs) {
            String apiKey = config.getApiKey();
            if (apiKey == null || apiKey.isEmpty()) {
                continue;
            }
            String secureKey = safeRetrieveApiKey(config.getId());
            if (!apiKey.equals(secureKey) && !safeStoreApiKey(config.getId(), apiKey)) {
                success = false;
            }
        }
        return success;
    }

    private void applyResolvedKeys(List<LlmProviderConfig> configs, Map<LlmProviderConfig, String> resolvedKeys) {
        for (LlmProviderConfig config : configs) {
            config.setApiKey(resolvedKeys.get(config));
        }
    }

    private String safeRetrieveApiKey(String providerId) {
        if (providerId == null || providerId.isEmpty()) {
            return ""; //$NON-NLS-1$
        }
        try {
            String value = apiKeyStorage.retrieveApiKey(providerId);
            return value != null ? value : ""; //$NON-NLS-1$
        } catch (RuntimeException e) {
            return ""; //$NON-NLS-1$
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
        String providerId = config.getId();
        String plaintext = config.getApiKey();
        String secureKey = ""; //$NON-NLS-1$
        if (providerId != null && !providerId.isEmpty()) {
            try {
                String stored = storage.retrieveApiKey(providerId);
                secureKey = stored != null ? stored : ""; //$NON-NLS-1$
            } catch (RuntimeException e) {
                secureKey = ""; //$NON-NLS-1$
            }
        }
        if (!secureKey.isEmpty()) {
            return secureKey;
        }
        if (plaintext != null && !plaintext.isEmpty() && providerId != null && !providerId.isEmpty()) {
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

        String retrieveApiKey(String providerId);

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
        public String retrieveApiKey(String providerId) {
            return SecureStorageUtil.retrieveApiKey(providerId);
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
