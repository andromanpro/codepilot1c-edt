/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Type-safe wrapper over tool parameters from LLM tool calls.
 *
 * <p>Provides typed access with clear error messages for missing
 * or invalid parameters.</p>
 */
public class ToolParameters {

    private final Map<String, Object> raw;

    public ToolParameters(Map<String, Object> parameters) {
        this.raw = parameters != null ? parameters : Collections.emptyMap();
    }

    /**
     * Returns the raw parameter map.
     */
    public Map<String, Object> getRaw() {
        return Collections.unmodifiableMap(raw);
    }

    /**
     * Gets a required string parameter.
     *
     * @param name parameter name
     * @return non-null string value
     * @throws ToolParameterException if missing or not a string
     */
    public String requireString(String name) {
        Object value = raw.get(name);
        if (value == null) {
            throw new ToolParameterException(
                    String.format("Required parameter '%s' is missing", name)); //$NON-NLS-1$
        }
        if (!(value instanceof String str)) {
            throw new ToolParameterException(
                    String.format("Parameter '%s' must be a string, got %s", //$NON-NLS-1$
                            name, value.getClass().getSimpleName()));
        }
        if (str.isBlank()) {
            throw new ToolParameterException(
                    String.format("Required parameter '%s' must not be blank", name)); //$NON-NLS-1$
        }
        return str;
    }

    /**
     * Gets a required long parameter.
     *
     * <p>Strict: rejects fractional values, NaN, Infinity, and out-of-range numbers.
     * The value must represent an exact integer within {@code long} range.</p>
     *
     * @param name parameter name
     * @return long value
     * @throws ToolParameterException if missing, not a number, or not an exact long
     */
    public long requireLong(String name) {
        Object value = raw.get(name);
        if (value == null) {
            throw new ToolParameterException(
                    String.format("Required parameter '%s' is missing", name)); //$NON-NLS-1$
        }
        if (value instanceof Number num) {
            return toExactLong(num, name);
        }
        if (value instanceof String str) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException e) {
                throw new ToolParameterException(
                        String.format("Parameter '%s' must be an integer, got '%s'", name, str)); //$NON-NLS-1$
            }
        }
        throw new ToolParameterException(
                String.format("Parameter '%s' must be an integer, got %s", //$NON-NLS-1$
                        name, value.getClass().getSimpleName()));
    }

    /**
     * Converts a {@link Number} to a {@code long}, rejecting fractional, NaN,
     * Infinity, and out-of-range values.
     */
    private static long toExactLong(Number num, String name) {
        if (num instanceof Long l) {
            return l;
        }
        if (num instanceof Integer i) {
            return i.longValue();
        }
        // Use BigDecimal for strict validation.
        String s = num.toString();
        try {
            BigDecimal bd = new BigDecimal(s);
            return bd.longValueExact();
        } catch (ArithmeticException e) {
            throw new ToolParameterException(
                    String.format("Parameter '%s' is out of long range: %s", name, s)); //$NON-NLS-1$
        } catch (NumberFormatException e) {
            throw new ToolParameterException(
                    String.format("Parameter '%s' must be an integer, got %s (NaN/Infinity/fractional)", name, s)); //$NON-NLS-1$
        }
    }

    /**
     * Gets an optional long parameter with fallback semantics.
     *
     * <p>Non-strict: returns {@code defaultValue} on any parse failure rather
     * than throwing. For strict validation use {@link #requireLong}.</p>
     *
     * @param name parameter name
     * @param defaultValue value if missing
     * @return long value or default
     */
    public long optLong(String name, long defaultValue) {
        Object value = raw.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number num) {
            // Best-effort: avoid truncation surprises, but don't throw.
            if (num instanceof Double || num instanceof Float) {
                double d = num.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    return defaultValue;
                }
                long lv = num.longValue();
                if (lv != d) {
                    return defaultValue;
                }
                return lv;
            }
            return num.longValue();
        }
        if (value instanceof String str) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * Gets an optional string parameter.
     *
     * @param name parameter name
     * @param defaultValue value if missing
     * @return string value or default
     */
    public String optString(String name, String defaultValue) {
        Object value = raw.get(name);
        if (value == null) {
            return defaultValue;
        }
        return value instanceof String str ? str : String.valueOf(value);
    }

    /**
     * Gets a required integer parameter.
     *
     * @param name parameter name
     * @return integer value
     * @throws ToolParameterException if missing or not a number
     */
    public int requireInt(String name) {
        Object value = raw.get(name);
        if (value == null) {
            throw new ToolParameterException(
                    String.format("Required parameter '%s' is missing", name)); //$NON-NLS-1$
        }
        if (value instanceof Number num) {
            return num.intValue();
        }
        if (value instanceof String str) {
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException e) {
                throw new ToolParameterException(
                        String.format("Parameter '%s' must be an integer, got '%s'", name, str)); //$NON-NLS-1$
            }
        }
        throw new ToolParameterException(
                String.format("Parameter '%s' must be an integer, got %s", //$NON-NLS-1$
                        name, value.getClass().getSimpleName()));
    }

    /**
     * Gets an optional integer parameter.
     *
     * @param name parameter name
     * @param defaultValue value if missing
     * @return integer value or default
     */
    public int optInt(String name, int defaultValue) {
        Object value = raw.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number num) {
            return num.intValue();
        }
        if (value instanceof String str) {
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * Gets an optional boolean parameter.
     *
     * @param name parameter name
     * @param defaultValue value if missing
     * @return boolean value or default
     */
    public boolean optBoolean(String name, boolean defaultValue) {
        Object value = raw.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String str) {
            return "true".equalsIgnoreCase(str); //$NON-NLS-1$
        }
        return defaultValue;
    }

    /**
     * Gets an optional list of strings parameter.
     *
     * @param name parameter name
     * @return list of strings (never null)
     */
    @SuppressWarnings("unchecked")
    public List<String> optStringList(String name) {
        Object value = raw.get(name);
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    /**
     * Checks if a parameter is present (non-null).
     */
    public boolean has(String name) {
        return raw.containsKey(name) && raw.get(name) != null;
    }

    /**
     * Exception for tool parameter validation errors.
     */
    public static class ToolParameterException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ToolParameterException(String message) {
            super(message);
        }
    }
}
