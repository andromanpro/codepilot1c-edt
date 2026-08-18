/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small non-interpolating parser for a hostile-input-safe key=value file. */
final class StrictPropertiesFile {
    static final int MAX_CONFIG_BYTES = 64 * 1024;

    private StrictPropertiesFile() {
    }

    static Map<String, String> read(Path file, boolean required) {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (required) throw error(ConfigurationErrorCode.INVALID_CONFIG_FILE, "config", "file is missing"); //$NON-NLS-1$ //$NON-NLS-2$
            return Map.of();
        }
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw error(ConfigurationErrorCode.UNSAFE_CONFIG_FILE, "config", "file must be a regular non-symlink"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        try {
            if (Files.size(file) > MAX_CONFIG_BYTES) {
                throw error(ConfigurationErrorCode.INVALID_CONFIG_FILE, "config", "file exceeds size limit"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            byte[] bytes = Files.readAllBytes(file);
            try {
                String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                return parse(text);
            } finally {
                java.util.Arrays.fill(bytes, (byte) 0);
            }
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw error(ConfigurationErrorCode.INVALID_CONFIG_FILE, "config", "file is not UTF-8"); //$NON-NLS-1$ //$NON-NLS-2$
        } catch (IOException exception) {
            throw error(ConfigurationErrorCode.INVALID_CONFIG_FILE, "config", "file cannot be read"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    static Map<String, String> parse(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        String[] lines = text.split("\\r?\\n", -1); //$NON-NLS-1$
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.indexOf('\r') >= 0 || line.indexOf('\0') >= 0) {
                throw error(ConfigurationErrorCode.INVALID_CONFIG_FILE, "config", "invalid character at line " + (index + 1)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue; //$NON-NLS-1$
            int equals = line.indexOf('=');
            if (equals <= 0) {
                throw error(ConfigurationErrorCode.INVALID_CONFIG_FILE, "config", "expected a key=value pair at line " + (index + 1)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            String key = line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();
            if (!key.matches("[a-z][a-zA-Z0-9.]*")) { //$NON-NLS-1$
                throw error(ConfigurationErrorCode.INVALID_CONFIG_FILE, "config", "unsupported syntax at line " + (index + 1)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (values.putIfAbsent(key, value) != null) {
                throw error(ConfigurationErrorCode.INVALID_CONFIG_FILE, "config", "duplicate key at line " + (index + 1)); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return Map.copyOf(values);
    }

    static ConfigurationException error(ConfigurationErrorCode code, String key, String detail) {
        return new ConfigurationException(code, key, detail);
    }
}
