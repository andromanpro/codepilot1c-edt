/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Small non-interpolating parser for a hostile-input-safe key=value file. */
final class StrictPropertiesFile {
    static final int MAX_CONFIG_BYTES = 64 * 1024;
    static final int MAX_SECRET_BYTES = 8 * 1024;

    private StrictPropertiesFile() {
    }

    static Map<String, String> read(Path file, boolean required, boolean protectParent) {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (required) throw error(ConfigurationErrorCode.INVALID_CONFIG_FILE, "config", "file is missing"); //$NON-NLS-1$ //$NON-NLS-2$
            return Map.of();
        }
        byte[] bytes = readBounded(file, MAX_CONFIG_BYTES, ConfigurationErrorCode.UNSAFE_CONFIG_FILE, "config", //$NON-NLS-1$
                false, protectParent);
        try {
            char[] characters = decodeUtf8(bytes, ConfigurationErrorCode.INVALID_CONFIG_FILE, "config"); //$NON-NLS-1$
            try {
                return parse(characters);
            } finally {
                Arrays.fill(characters, '\0');
            }
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    static byte[] readSecret(Path file) {
        return readBounded(file, MAX_SECRET_BYTES, ConfigurationErrorCode.UNSAFE_SECRET_FILE,
                RuntimeSetting.PROVIDER_API_KEY_FILE.key(), true, false);
    }

    /**
     * Opens with NOFOLLOW_LINKS, then checks attributes before and after open.
     * Java does not expose an fd-backed attribute view on every filesystem, so
     * file-key comparison is the strongest portable identity verification.
     */
    private static byte[] readBounded(Path file, int maximum, ConfigurationErrorCode unsafeCode, String setting,
            boolean requirePrivatePermissions, boolean protectParent) {
        try {
            BasicFileAttributes before = attributes(file, unsafeCode, setting);
            checkPermissions(file, unsafeCode, setting, requirePrivatePermissions);
            if (protectParent) checkDefaultParent(file, unsafeCode, setting);
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes after = attributes(file, unsafeCode, setting);
                if (!sameIdentity(before, after)) {
                    throw error(unsafeCode, setting, "file changed while being opened"); //$NON-NLS-1$
                }
                checkPermissions(file, unsafeCode, setting, requirePrivatePermissions);
                if (protectParent) checkDefaultParent(file, unsafeCode, setting);
                return readAtMost(channel, maximum, unsafeCode, setting);
            }
        } catch (ConfigurationException exception) {
            throw exception;
        } catch (IOException | UnsupportedOperationException exception) {
            throw error(unsafeCode, setting, "file cannot be opened safely"); //$NON-NLS-1$
        }
    }

    private static BasicFileAttributes attributes(Path file, ConfigurationErrorCode code, String setting) throws IOException {
        if (Files.isSymbolicLink(file)) {
            throw error(code, setting, "file must be a regular non-symlink"); //$NON-NLS-1$
        }
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw error(code, setting, "file must be regular"); //$NON-NLS-1$
        return attributes;
    }

    private static boolean sameIdentity(BasicFileAttributes before, BasicFileAttributes after) {
        Object beforeKey = before.fileKey();
        Object afterKey = after.fileKey();
        if (beforeKey != null || afterKey != null) return beforeKey != null && beforeKey.equals(afterKey);
        return before.size() == after.size() && before.lastModifiedTime().equals(after.lastModifiedTime());
    }

    private static byte[] readAtMost(FileChannel channel, int maximum, ConfigurationErrorCode code, String setting)
            throws IOException {
        byte[] bounded = new byte[maximum + 1];
        ByteBuffer target = ByteBuffer.wrap(bounded);
        try {
            while (target.hasRemaining()) {
                int count = channel.read(target);
                if (count < 0) break;
            }
            if (target.position() > maximum) throw error(code == ConfigurationErrorCode.UNSAFE_CONFIG_FILE
                    ? ConfigurationErrorCode.INVALID_CONFIG_FILE : ConfigurationErrorCode.SECRET_TOO_LARGE,
                    setting, "file exceeds size limit"); //$NON-NLS-1$
            return Arrays.copyOf(bounded, target.position());
        } finally {
            Arrays.fill(bounded, (byte) 0);
        }
    }

    private static void checkPermissions(Path file, ConfigurationErrorCode code, String setting, boolean privateFile)
            throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
            Set<PosixFilePermission> forbidden = privateFile
                    ? EnumSet.of(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                            PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE)
                    : EnumSet.of(PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE);
            for (PosixFilePermission permission : forbidden) {
                if (permissions.contains(permission)) throw error(code, setting, "file permissions are too broad"); //$NON-NLS-1$
            }
        } catch (UnsupportedOperationException exception) {
            // Windows ACL verification is unavailable in the Java 17 portable API.
        }
    }

    private static void checkDefaultParent(Path file, ConfigurationErrorCode code, String setting) throws IOException {
        Path parent = file.toAbsolutePath().normalize().getParent();
        if (parent == null || Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw error(code, setting, "default config parent must be a non-symlink directory"); //$NON-NLS-1$
        }
        checkPermissions(parent, code, setting, false);
    }

    static char[] decodeUtf8(byte[] bytes, ConfigurationErrorCode code, String setting) {
        CharBuffer buffer = null;
        try {
            buffer = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
            char[] characters = new char[buffer.remaining()];
            buffer.get(characters);
            return characters;
        } catch (CharacterCodingException exception) {
            throw error(code, setting, "file is not UTF-8"); //$NON-NLS-1$
        } finally {
            wipe(buffer);
        }
    }

    private static void wipe(CharBuffer buffer) {
        if (buffer != null && buffer.hasArray()) Arrays.fill(buffer.array(), '\0');
    }

    static Map<String, String> parse(char[] characters) {
        Map<String, String> values = new LinkedHashMap<>();
        int lineStart = 0;
        for (int index = 0; index <= characters.length; index++) {
            if (index != characters.length && characters[index] != '\n') continue;
            parseLine(characters, lineStart, index, values);
            lineStart = index + 1;
        }
        return Map.copyOf(values);
    }

    private static void parseLine(char[] input, int start, int end, Map<String, String> values) {
        int first = start;
        while (first < end && Character.isWhitespace(input[first])) first++;
        if (first == end || input[first] == '#') return;
        int equals = -1;
        for (int index = start; index < end; index++) {
            if (input[index] == '\r' || input[index] == '\0') {
                throw error(ConfigurationErrorCode.INVALID_CONFIG_FILE, "config", "invalid character"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (input[index] == '=' && equals < 0) equals = index;
        }
        if (equals <= start) throw error(ConfigurationErrorCode.INVALID_CONFIG_FILE, "config", "expected a key=value pair"); //$NON-NLS-1$ //$NON-NLS-2$
        int keyStart = trimStart(input, start, equals);
        int keyEnd = trimEnd(input, keyStart, equals);
        String key = new String(input, keyStart, keyEnd - keyStart);
        if (!key.matches("[A-Za-z][A-Za-z0-9._-]*")) { //$NON-NLS-1$
            throw error(ConfigurationErrorCode.INVALID_CONFIG_FILE, "config", "unsupported syntax"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (looksLikeSecretKey(key)) {
            throw error(ConfigurationErrorCode.INVALID_CONFIG_FILE, "config", "inline secret key is forbidden"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        int valueStart = trimStart(input, equals + 1, end);
        int valueEnd = trimEnd(input, valueStart, end);
        String value = new String(input, valueStart, valueEnd - valueStart);
        if (values.putIfAbsent(key, value) != null) {
            throw error(ConfigurationErrorCode.INVALID_CONFIG_FILE, "config", "duplicate key"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static int trimStart(char[] input, int start, int end) {
        while (start < end && Character.isWhitespace(input[start])) start++;
        return start;
    }

    private static int trimEnd(char[] input, int start, int end) {
        while (end > start && Character.isWhitespace(input[end - 1])) end--;
        return end;
    }

    static boolean looksLikeSecretKey(String key) {
        String normalized = key.replace("_", "").replace(".", "").replace("-", "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .toLowerCase(java.util.Locale.ROOT);
        if ("providerapikeyfile".equals(normalized)) return false; //$NON-NLS-1$
        return normalized.contains("apikey") || normalized.contains("secret") || normalized.contains("token") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || normalized.contains("password") || normalized.contains("authorization"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    static ConfigurationException error(ConfigurationErrorCode code, String key, String detail) {
        return new ConfigurationException(code, key, detail);
    }
}
