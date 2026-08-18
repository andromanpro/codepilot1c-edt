package com.codepilot1c.core.tools.diagnostics;

/**
 * Masks secret-bearing 1C command-line parameters from EDT infobase access settings.
 */
final class InfobaseAccessParameterMasking {

    private static final String PASSWORD_SWITCH = "/P"; //$NON-NLS-1$
    private static final String PREFETCH_SWITCH = "/Prefetch"; //$NON-NLS-1$
    private static final String CONNECTION_STRING_SWITCH = "/IBConnectionString"; //$NON-NLS-1$
    private static final String REDACTED_PASSWORD = "/P <redacted>"; //$NON-NLS-1$
    private static final String REDACTED_VALUE = "<redacted>"; //$NON-NLS-1$

    private InfobaseAccessParameterMasking() {
    }

    static String mask(String additionalParameters) {
        if (additionalParameters == null || additionalParameters.isBlank()) {
            return additionalParameters;
        }

        StringBuilder masked = new StringBuilder(additionalParameters.length());
        int cursor = 0;
        while (cursor < additionalParameters.length()) {
            if (!isSwitchStart(additionalParameters, cursor)) {
                masked.append(additionalParameters.charAt(cursor++));
                continue;
            }

            if (matchesNamedSwitch(additionalParameters, cursor, CONNECTION_STRING_SWITCH)) {
                int valueOffset = cursor + CONNECTION_STRING_SWITCH.length();
                ValueSpan value = parseValue(additionalParameters, valueOffset);
                if (value != null && containsPasswordAssignment(additionalParameters,
                        value.contentStart(), value.contentEnd())) {
                    masked.append(additionalParameters, cursor, value.contentStart());
                    masked.append(REDACTED_VALUE);
                    masked.append(additionalParameters, value.contentEnd(), value.end());
                    cursor = value.end();
                    continue;
                }
            }

            if (matchesPasswordSwitch(additionalParameters, cursor)) {
                ValueSpan value = parseValue(additionalParameters, cursor + PASSWORD_SWITCH.length());
                if (value != null) {
                    masked.append(REDACTED_PASSWORD);
                    cursor = value.end();
                    continue;
                }
            }

            masked.append(additionalParameters.charAt(cursor++));
        }
        return masked.toString();
    }

    static boolean isMasked(String original, String masked) {
        return original != null && !original.equals(masked);
    }

    private static boolean isSwitchStart(String value, int offset) {
        return value.charAt(offset) == '/' && (offset == 0 || Character.isWhitespace(value.charAt(offset - 1)));
    }

    private static boolean matchesPasswordSwitch(String value, int offset) {
        if (!startsWithIgnoreCase(value, offset, PASSWORD_SWITCH)
                || startsWithNamedSwitch(value, offset, PREFETCH_SWITCH)) {
            return false;
        }
        return offset + PASSWORD_SWITCH.length() < value.length();
    }

    private static boolean matchesNamedSwitch(String value, int offset, String switchName) {
        if (!startsWithIgnoreCase(value, offset, switchName)) {
            return false;
        }
        int afterName = offset + switchName.length();
        return afterName == value.length() || Character.isWhitespace(value.charAt(afterName))
                || value.charAt(afterName) == '"';
    }

    private static boolean startsWithNamedSwitch(String value, int offset, String switchName) {
        if (!startsWithIgnoreCase(value, offset, switchName)) {
            return false;
        }
        int afterName = offset + switchName.length();
        return afterName == value.length() || Character.isWhitespace(value.charAt(afterName));
    }

    private static boolean startsWithIgnoreCase(String value, int offset, String expected) {
        return offset + expected.length() <= value.length()
                && value.regionMatches(true, offset, expected, 0, expected.length());
    }

    private static ValueSpan parseValue(String value, int offset) {
        int start = offset;
        while (start < value.length() && Character.isWhitespace(value.charAt(start))) {
            start++;
        }
        if (start >= value.length() || value.charAt(start) == '/') {
            return null;
        }

        if (value.charAt(start) == '"') {
            int closingQuote = findClosingQuote(value, start);
            if (closingQuote < 0) {
                return new ValueSpan(start + 1, value.length(), value.length());
            }
            return new ValueSpan(start + 1, closingQuote, closingQuote + 1);
        }

        int end = findNextSwitch(value, start);
        while (end > start && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return end == start ? null : new ValueSpan(start, end, end);
    }

    private static int findClosingQuote(String value, int openingQuote) {
        int cursor = openingQuote + 1;
        while (cursor < value.length()) {
            char current = value.charAt(cursor);
            if (current == '\\' && cursor + 1 < value.length()) {
                cursor += 2;
                continue;
            }
            if (current == '"') {
                if (cursor + 1 < value.length() && value.charAt(cursor + 1) == '"') {
                    cursor += 2;
                    continue;
                }
                return cursor;
            }
            cursor++;
        }
        return -1;
    }

    private static int findNextSwitch(String value, int start) {
        int cursor = start;
        while (cursor < value.length()) {
            if (!Character.isWhitespace(value.charAt(cursor))) {
                cursor++;
                continue;
            }
            int whitespaceStart = cursor;
            while (cursor < value.length() && Character.isWhitespace(value.charAt(cursor))) {
                cursor++;
            }
            if (cursor < value.length() && value.charAt(cursor) == '/') {
                return whitespaceStart;
            }
        }
        return value.length();
    }

    private static boolean containsPasswordAssignment(String value, int start, int end) {
        int fieldStart = start;
        while (fieldStart < end) {
            while (fieldStart < end
                    && (value.charAt(fieldStart) == ';' || Character.isWhitespace(value.charAt(fieldStart)))) {
                fieldStart++;
            }
            int equals = fieldStart;
            while (equals < end && value.charAt(equals) != '=' && value.charAt(equals) != ';') {
                equals++;
            }
            if (equals < end && value.charAt(equals) == '=') {
                int keyEnd = equals;
                while (keyEnd > fieldStart && Character.isWhitespace(value.charAt(keyEnd - 1))) {
                    keyEnd--;
                }
                if (isPasswordKey(value, fieldStart, keyEnd)) {
                    return true;
                }
            }
            while (equals < end && value.charAt(equals) != ';') {
                equals++;
            }
            fieldStart = equals + 1;
        }
        return false;
    }

    private static boolean isPasswordKey(String value, int start, int end) {
        int length = end - start;
        return length == 3 && value.regionMatches(true, start, "Pwd", 0, 3) //$NON-NLS-1$
                || length == 8 && value.regionMatches(true, start, "Password", 0, 8); //$NON-NLS-1$
    }

    private record ValueSpan(int contentStart, int contentEnd, int end) {
    }
}
