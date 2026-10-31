/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.settings;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;

import com.codepilot1c.core.internal.VibeCorePlugin;

/**
 * Utility class for secure storage of sensitive data like API keys.
 *
 * <p>Uses Eclipse Secure Storage to encrypt sensitive information.</p>
 */
public final class SecureStorageUtil {

    private static final String SECURE_NODE_PATH = "/" + VibeCorePlugin.PLUGIN_ID; //$NON-NLS-1$

    private SecureStorageUtil() {
        // Utility class
    }

    /** Outcome of reading an API key from secure storage. */
    public enum ApiKeyReadStatus {
        PRESENT,
        ABSENT,
        READ_FAILED
    }

    /**
     * Result of an API-key read. A failed read is deliberately distinct from an absent key so
     * callers cannot safely mutate configuration based on an unknown previous credential.
     */
    public record ApiKeyReadResult(ApiKeyReadStatus status, String value) {
        public ApiKeyReadResult {
            if (status == null) {
                throw new IllegalArgumentException("status must not be null"); //$NON-NLS-1$
            }
            value = value != null ? value : ""; //$NON-NLS-1$
            if (status != ApiKeyReadStatus.PRESENT && !value.isEmpty()) {
                throw new IllegalArgumentException("only a present result may contain a value"); //$NON-NLS-1$
            }
        }

        public static ApiKeyReadResult present(String value) {
            if (value == null || value.isEmpty()) {
                return absent();
            }
            return new ApiKeyReadResult(ApiKeyReadStatus.PRESENT, value);
        }

        public static ApiKeyReadResult absent() {
            return new ApiKeyReadResult(ApiKeyReadStatus.ABSENT, ""); //$NON-NLS-1$
        }

        public static ApiKeyReadResult readFailed() {
            return new ApiKeyReadResult(ApiKeyReadStatus.READ_FAILED, ""); //$NON-NLS-1$
        }

        public boolean isPresent() {
            return status == ApiKeyReadStatus.PRESENT;
        }

        public boolean isReadFailed() {
            return status == ApiKeyReadStatus.READ_FAILED;
        }
    }

    /**
     * Stores a value securely.
     *
     * @param key   the key
     * @param value the value to store
     * @return true if stored successfully
     */
    public static boolean storeSecurely(String key, String value) {
        try {
            ISecurePreferences root = SecurePreferencesFactory.getDefault();
            ISecurePreferences node = root.node(SECURE_NODE_PATH);
            node.put(key, value, true); // true = encrypt
            node.flush();
            return true;
        } catch (Exception e) {
            VibeCorePlugin.logError("Failed to store secure value for key: " + key, e); //$NON-NLS-1$
            return false;
        }
    }

    /**
     * Retrieves a value from secure storage.
     *
     * @param key          the key
     * @param defaultValue the default value if not found
     * @return the stored value or default
     */
    public static String retrieveSecurely(String key, String defaultValue) {
        try {
            ISecurePreferences root = SecurePreferencesFactory.getDefault();
            ISecurePreferences node = root.node(SECURE_NODE_PATH);
            return node.get(key, defaultValue);
        } catch (StorageException e) {
            // Log error: "Failed to retrieve secure value for key: {}", key, e //$NON-NLS-1$
            return defaultValue;
        }
    }

    /**
     * Removes a value from secure storage.
     *
     * @param key the key to remove
     */
    public static void removeSecurely(String key) {
        try {
            ISecurePreferences root = SecurePreferencesFactory.getDefault();
            ISecurePreferences node = root.node(SECURE_NODE_PATH);
            node.remove(key);
            node.flush();
        } catch (Exception e) {
            // Log error: "Failed to remove secure value for key: {}", key, e //$NON-NLS-1$
        }
    }

    /**
     * Checks if secure storage is available.
     *
     * @return true if available
     */
    public static boolean isAvailable() {
        try {
            ISecurePreferences root = SecurePreferencesFactory.getDefault();
            return root != null;
        } catch (Exception e) {
            // Log warn: "Secure storage not available", e //$NON-NLS-1$
            return false;
        }
    }

    /**
     * Stores an API key securely.
     *
     * @param providerId the provider ID
     * @param apiKey     the API key
     * @return true if stored successfully
     */
    public static boolean storeApiKey(String providerId, String apiKey) {
        return storeSecurelySilently(providerId + ".apiKey", apiKey); //$NON-NLS-1$
    }

    /**
     * Retrieves an API key from secure storage.
     *
     * @param providerId the provider ID
     * @return a result distinguishing a present key, an absent key, and a failed read
     */
    public static ApiKeyReadResult readApiKey(String providerId) {
        try {
            ISecurePreferences root = SecurePreferencesFactory.getDefault();
            ISecurePreferences node = root.node(SECURE_NODE_PATH);
            String value = node.get(providerId + ".apiKey", null); //$NON-NLS-1$
            return ApiKeyReadResult.present(value);
        } catch (Exception e) {
            // The caller owns sanitized, rate-limited diagnostics for provider credentials.
            return ApiKeyReadResult.readFailed();
        }
    }

    /**
     * Retrieves an API key, preserving the legacy empty-string fallback for external callers.
     * Transactional configuration code must use {@link #readApiKey(String)} instead.
     *
     * @param providerId the provider ID
     * @return the API key or an empty string when absent or unreadable
     */
    public static String retrieveApiKey(String providerId) {
        ApiKeyReadResult result = readApiKey(providerId);
        return result.isPresent() ? result.value() : ""; //$NON-NLS-1$
    }

    /**
     * Removes a provider API key from secure storage.
     *
     * @param providerId the provider ID
     * @return true if the key was removed successfully
     */
    public static boolean removeApiKey(String providerId) {
        try {
            ISecurePreferences root = SecurePreferencesFactory.getDefault();
            ISecurePreferences node = root.node(SECURE_NODE_PATH);
            node.remove(providerId + ".apiKey"); //$NON-NLS-1$
            node.flush();
            return true;
        } catch (Exception e) {
            // The caller owns sanitized, rate-limited diagnostics for provider credentials.
            return false;
        }
    }

    private static boolean storeSecurelySilently(String key, String value) {
        try {
            ISecurePreferences root = SecurePreferencesFactory.getDefault();
            ISecurePreferences node = root.node(SECURE_NODE_PATH);
            node.put(key, value, true);
            node.flush();
            return true;
        } catch (Exception e) {
            // The caller owns sanitized, rate-limited diagnostics for provider credentials.
            return false;
        }
    }
}
