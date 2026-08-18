package com.codepilot1c.core.provider.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;
import org.osgi.service.prefs.BackingStoreException;

import com.codepilot1c.core.provider.config.LlmProviderConfigStore.ApiKeyStorage;
import com.codepilot1c.core.provider.config.LlmProviderConfigStore.PreferenceAccess;
import com.codepilot1c.core.settings.VibePreferenceConstants;
import com.google.gson.Gson;

public class LlmProviderConfigStoreMigrationTest {

    private static final String SECRET_A = "legacy-secret-alpha"; //$NON-NLS-1$
    private static final String SECRET_B = "legacy-secret-beta"; //$NON-NLS-1$

    @Test
    public void migratesVersionZeroAndOneOnlyAfterSecureWrites() {
        for (int version : new int[] { 0, 1 }) {
            FakePreferences preferences = legacyPreferences(version,
                    configured("provider-" + version, SECRET_A)); //$NON-NLS-1$
            FakeApiKeyStorage storage = new FakeApiKeyStorage();
            List<String> warnings = new ArrayList<>();

            LlmProviderConfigStore store = new LlmProviderConfigStore(preferences, storage, warnings::add);
            LlmProviderConfig loaded = store.getProviders().get(0);

            assertEquals(2, preferences.getInt(VibePreferenceConstants.PREF_LLM_CONFIG_VERSION, -1));
            assertEquals(SECRET_A, storage.keys.get("provider-" + version)); //$NON-NLS-1$
            assertEquals(SECRET_A, loaded.getApiKey());
            assertFalse(preferences.providersJson().contains(SECRET_A));
            assertFalse(preferences.providersJson().contains("apiKey")); //$NON-NLS-1$
            assertTrue(warnings.isEmpty());
        }
    }

    @Test
    public void partialFailurePreservesAllPlaintextAndRetriesNextLoad() {
        FakePreferences preferences = legacyPreferences(1,
                configured("first", SECRET_A), configured("second", SECRET_B)); //$NON-NLS-1$ //$NON-NLS-2$
        FakeApiKeyStorage storage = new FakeApiKeyStorage();
        storage.failStores.add("second"); //$NON-NLS-1$
        List<String> warnings = new ArrayList<>();
        LlmProviderConfigStore store = new LlmProviderConfigStore(preferences, storage, warnings::add);

        List<LlmProviderConfig> firstLoad = store.getProviders();

        assertEquals(1, preferences.getInt(VibePreferenceConstants.PREF_LLM_CONFIG_VERSION, -1));
        assertTrue(preferences.providersJson().contains(SECRET_A));
        assertTrue(preferences.providersJson().contains(SECRET_B));
        assertEquals(SECRET_A, firstLoad.get(0).getApiKey());
        assertEquals(SECRET_B, firstLoad.get(1).getApiKey());
        assertEquals(1, warnings.size());

        store.refresh();

        assertEquals(1, preferences.getInt(VibePreferenceConstants.PREF_LLM_CONFIG_VERSION, -1));
        assertTrue(preferences.providersJson().contains(SECRET_A));
        assertTrue(preferences.providersJson().contains(SECRET_B));
        assertEquals(1, warnings.size());

        storage.failStores.clear();
        store.refresh();

        assertEquals(2, preferences.getInt(VibePreferenceConstants.PREF_LLM_CONFIG_VERSION, -1));
        assertFalse(preferences.providersJson().contains(SECRET_A));
        assertFalse(preferences.providersJson().contains(SECRET_B));
        assertEquals(3, storage.storeAttempts.get("first").intValue()); //$NON-NLS-1$
        assertEquals(3, storage.storeAttempts.get("second").intValue()); //$NON-NLS-1$
        assertEquals(1, warnings.size());
    }

    @Test
    public void versionTwoFallbackIsRemigratedAndSecureValueWins() {
        FakePreferences fallbackPreferences = legacyPreferences(2, configured("fallback", SECRET_A)); //$NON-NLS-1$
        FakeApiKeyStorage fallbackStorage = new FakeApiKeyStorage();
        LlmProviderConfigStore fallbackStore = new LlmProviderConfigStore(
                fallbackPreferences, fallbackStorage, message -> { });

        assertEquals(SECRET_A, fallbackStore.getProviders().get(0).getApiKey());
        assertEquals(SECRET_A, fallbackStorage.keys.get("fallback")); //$NON-NLS-1$
        assertFalse(fallbackPreferences.providersJson().contains(SECRET_A));

        FakePreferences securePreferences = legacyPreferences(2, configured("secure-first", SECRET_A)); //$NON-NLS-1$
        FakeApiKeyStorage secureStorage = new FakeApiKeyStorage();
        secureStorage.keys.put("secure-first", SECRET_B); //$NON-NLS-1$
        LlmProviderConfigStore secureStore = new LlmProviderConfigStore(
                securePreferences, secureStorage, message -> { });

        assertEquals(SECRET_B, secureStore.getProviders().get(0).getApiKey());
        assertFalse(securePreferences.providersJson().contains(SECRET_A));
        assertEquals(0, secureStorage.storeAttempts.getOrDefault("secure-first", 0).intValue()); //$NON-NLS-1$
    }

    @Test
    public void unavailableStorageKeepsVersionTwoFallbackForFutureRetry() {
        FakePreferences preferences = legacyPreferences(2,
                configured("stored", SECRET_A), configured("fallback", SECRET_B)); //$NON-NLS-1$ //$NON-NLS-2$
        String originalJson = preferences.providersJson();
        FakeApiKeyStorage storage = new FakeApiKeyStorage();
        storage.failStores.add("fallback"); //$NON-NLS-1$
        List<String> warnings = new ArrayList<>();
        LlmProviderConfigStore store = new LlmProviderConfigStore(preferences, storage, warnings::add);

        assertEquals(SECRET_A, store.getProviders().get(0).getApiKey());
        assertEquals(SECRET_B, store.getProviders().get(1).getApiKey());
        assertEquals(SECRET_A, storage.keys.get("stored")); //$NON-NLS-1$
        assertEquals(originalJson, preferences.providersJson());
        assertEquals(2, preferences.getInt(VibePreferenceConstants.PREF_LLM_CONFIG_VERSION, -1));
        assertEquals(1, warnings.size());
    }

    @Test
    public void versionTwoSaveStripsPlaintextAndDeleteRemovesSecureKey() {
        FakePreferences preferences = legacyPreferences(2);
        FakeApiKeyStorage storage = new FakeApiKeyStorage();
        List<String> warnings = new ArrayList<>();
        LlmProviderConfigStore store = new LlmProviderConfigStore(preferences, storage, warnings::add);
        LlmProviderConfig config = configured("new-provider", SECRET_A); //$NON-NLS-1$

        store.addProvider(config);

        assertEquals(SECRET_A, storage.keys.get("new-provider")); //$NON-NLS-1$
        assertFalse(preferences.providersJson().contains(SECRET_A));
        assertFalse(preferences.providersJson().contains("apiKey")); //$NON-NLS-1$

        store.removeProvider("new-provider"); //$NON-NLS-1$

        assertNull(storage.keys.get("new-provider")); //$NON-NLS-1$
        assertEquals(List.of("new-provider"), storage.removeAttempts); //$NON-NLS-1$
        assertEquals("[]", preferences.providersJson()); //$NON-NLS-1$
        assertTrue(warnings.isEmpty());
    }

    @Test
    public void localProviderWithoutKeyMigratesAndRemainsConfigured() {
        LlmProviderConfig local = configured("ollama", null); //$NON-NLS-1$
        local.setType(ProviderType.OLLAMA);
        FakePreferences preferences = legacyPreferences(0, local);
        LlmProviderConfigStore store = new LlmProviderConfigStore(
                preferences, new FakeApiKeyStorage(), message -> { });

        assertTrue(store.getProviders().get(0).isConfigured());
        assertEquals(2, preferences.getInt(VibePreferenceConstants.PREF_LLM_CONFIG_VERSION, -1));
    }

    @Test
    public void storageFailureDiagnosticsNeverContainSecretMaterial() {
        FakePreferences preferences = legacyPreferences(0, configured("diagnostic", SECRET_A)); //$NON-NLS-1$
        FakeApiKeyStorage storage = new FakeApiKeyStorage();
        storage.throwOnStore = new IllegalStateException("failure includes " + SECRET_A); //$NON-NLS-1$
        List<String> warnings = new ArrayList<>();
        LlmProviderConfigStore store = new LlmProviderConfigStore(preferences, storage, warnings::add);

        LlmProviderConfig loaded = store.getProviders().get(0);

        assertTrue(preferences.providersJson().contains(SECRET_A));
        assertEquals(0, preferences.getInt(VibePreferenceConstants.PREF_LLM_CONFIG_VERSION, -1));
        assertFalse(String.join(" ", warnings).contains(SECRET_A)); //$NON-NLS-1$
        assertFalse(loaded.toString().contains(SECRET_A));
    }

    private static FakePreferences legacyPreferences(int version, LlmProviderConfig... configs) {
        FakePreferences preferences = new FakePreferences();
        preferences.put(VibePreferenceConstants.PREF_LLM_PROVIDERS, new Gson().toJson(configs));
        preferences.putInt(VibePreferenceConstants.PREF_LLM_CONFIG_VERSION, version);
        return preferences;
    }

    private static LlmProviderConfig configured(String id, String apiKey) {
        return new LlmProviderConfig(id, id, ProviderType.OPENAI_COMPATIBLE,
                "https://example.com/v1", apiKey, "model", 4096); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static final class FakePreferences implements PreferenceAccess {
        private final Map<String, String> strings = new HashMap<>();
        private final Map<String, Integer> integers = new HashMap<>();

        @Override
        public String get(String key, String defaultValue) {
            return strings.getOrDefault(key, defaultValue);
        }

        @Override
        public int getInt(String key, int defaultValue) {
            return integers.getOrDefault(key, defaultValue);
        }

        @Override
        public void put(String key, String value) {
            strings.put(key, value);
        }

        @Override
        public void putInt(String key, int value) {
            integers.put(key, value);
        }

        @Override
        public void flush() throws BackingStoreException {
            // In-memory test double.
        }

        String providersJson() {
            return get(VibePreferenceConstants.PREF_LLM_PROVIDERS, "[]"); //$NON-NLS-1$
        }
    }

    static final class FakeApiKeyStorage implements ApiKeyStorage {
        final Map<String, String> keys = new HashMap<>();
        final Map<String, Integer> storeAttempts = new HashMap<>();
        final Set<String> failStores = new HashSet<>();
        final List<String> removeAttempts = new ArrayList<>();
        RuntimeException throwOnStore;

        @Override
        public boolean storeApiKey(String providerId, String apiKey) {
            storeAttempts.merge(providerId, 1, Integer::sum);
            if (throwOnStore != null) {
                throw throwOnStore;
            }
            if (failStores.contains(providerId)) {
                return false;
            }
            keys.put(providerId, apiKey);
            return true;
        }

        @Override
        public String retrieveApiKey(String providerId) {
            return keys.getOrDefault(providerId, ""); //$NON-NLS-1$
        }

        @Override
        public boolean removeApiKey(String providerId) {
            removeAttempts.add(providerId);
            keys.remove(providerId);
            return true;
        }
    }
}
