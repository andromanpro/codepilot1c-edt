/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.tools.ToolParameters.ToolParameterException;

/**
 * Tests strict {@code requireLong} behaviour: rejects fractional, NaN, Infinity,
 * and out-of-range values.
 */
public class ToolParametersLongTest {

    // ---- Exact integer values (must succeed) ----

    @Test
    public void requireLongAcceptsInteger() {
        ToolParameters p = new ToolParameters(Map.of("rev", 42)); //$NON-NLS-1$
        assertEquals(42L, p.requireLong("rev")); //$NON-NLS-1$
    }

    @Test
    public void requireLongAcceptsLong() {
        ToolParameters p = new ToolParameters(Map.of("rev", 3000000000L)); //$NON-NLS-1$
        assertEquals(3000000000L, p.requireLong("rev")); //$NON-NLS-1$
    }

    @Test
    public void requireLongAcceptsLongString() {
        ToolParameters p = new ToolParameters(Map.of("rev", "99")); //$NON-NLS-1$
        assertEquals(99L, p.requireLong("rev")); //$NON-NLS-1$
    }

    @Test
    public void requireLongAcceptsZero() {
        ToolParameters p = new ToolParameters(Map.of("rev", 0)); //$NON-NLS-1$
        assertEquals(0L, p.requireLong("rev")); //$NON-NLS-1$
    }

    @Test
    public void requireLongAcceptsNegativeLong() {
        ToolParameters p = new ToolParameters(Map.of("rev", -12345678901L)); //$NON-NLS-1$
        assertEquals(-12345678901L, p.requireLong("rev")); //$NON-NLS-1$
    }

    @Test
    public void requireLongAcceptsLongMaxValue() {
        ToolParameters p = new ToolParameters(Map.of("rev", Long.MAX_VALUE)); //$NON-NLS-1$
        assertEquals(Long.MAX_VALUE, p.requireLong("rev")); //$NON-NLS-1$
    }

    @Test
    public void requireLongAcceptsLongMinValue() {
        ToolParameters p = new ToolParameters(Map.of("rev", Long.MIN_VALUE)); //$NON-NLS-1$
        assertEquals(Long.MIN_VALUE, p.requireLong("rev")); //$NON-NLS-1$
    }

    // ---- Fractional values (must fail) ----

    @Test
    public void requireLongRejectsFractionalDouble() {
        ToolParameters p = new ToolParameters(Map.of("rev", 1.5)); //$NON-NLS-1$
        try {
            p.requireLong("rev"); //$NON-NLS-1$
            fail("expected ToolParameterException for fractional value"); //$NON-NLS-1$
        } catch (ToolParameterException e) {
            // expected
        }
    }

    @Test
    public void requireLongRejectsFractionalFloat() {
        ToolParameters p = new ToolParameters(Map.of("rev", 42.5f)); //$NON-NLS-1$
        try {
            p.requireLong("rev"); //$NON-NLS-1$
            fail("expected ToolParameterException"); //$NON-NLS-1$
        } catch (ToolParameterException e) {
            // expected
        }
    }

    // ---- NaN / Infinity (must fail) ----

    @Test
    public void requireLongRejectsNaN() {
        ToolParameters p = new ToolParameters(Map.of("rev", Double.NaN)); //$NON-NLS-1$
        try {
            p.requireLong("rev"); //$NON-NLS-1$
            fail("expected ToolParameterException for NaN"); //$NON-NLS-1$
        } catch (ToolParameterException e) {
            // expected
        }
    }

    @Test
    public void requireLongRejectsPositiveInfinity() {
        ToolParameters p = new ToolParameters(Map.of("rev", Double.POSITIVE_INFINITY)); //$NON-NLS-1$
        try {
            p.requireLong("rev"); //$NON-NLS-1$
            fail("expected ToolParameterException for Infinity"); //$NON-NLS-1$
        } catch (ToolParameterException e) {
            // expected
        }
    }

    @Test
    public void requireLongRejectsNegativeInfinity() {
        ToolParameters p = new ToolParameters(Map.of("rev", Double.NEGATIVE_INFINITY)); //$NON-NLS-1$
        try {
            p.requireLong("rev"); //$NON-NLS-1$
            fail("expected ToolParameterException for -Infinity"); //$NON-NLS-1$
        } catch (ToolParameterException e) {
            // expected
        }
    }

    // ---- Out of range (must fail) ----

    @Test
    public void requireLongRejectsDoubleBeyondLongMax() {
        // 1e30 is representable as double but out of long range
        ToolParameters p = new ToolParameters(Map.of("rev", 1e30)); //$NON-NLS-1$
        try {
            p.requireLong("rev"); //$NON-NLS-1$
            fail("expected ToolParameterException for out-of-range double"); //$NON-NLS-1$
        } catch (ToolParameterException e) {
            // expected
        }
    }

    @Test
    public void requireLongRejectsBigIntegerBeyondLongMax() {
        java.math.BigInteger big = java.math.BigInteger.valueOf(Long.MAX_VALUE).add(java.math.BigInteger.ONE);
        ToolParameters p = new ToolParameters(Map.of("rev", new java.math.BigDecimal(big))); //$NON-NLS-1$
        try {
            p.requireLong("rev"); //$NON-NLS-1$
            fail("expected ToolParameterException for BigInteger beyond Long.MAX_VALUE"); //$NON-NLS-1$
        } catch (ToolParameterException e) {
            // expected
        }
    }

    // ---- Missing / wrong type ----

    @Test
    public void requireLongRejectsMissingKey() {
        ToolParameters p = new ToolParameters(Map.of("other", 42)); //$NON-NLS-1$
        try {
            p.requireLong("rev"); //$NON-NLS-1$
            fail("expected ToolParameterException for missing key"); //$NON-NLS-1$
        } catch (ToolParameterException e) {
            // expected
        }
    }

    @Test
    public void requireLongRejectsNonNumericString() {
        ToolParameters p = new ToolParameters(Map.of("rev", "abc")); //$NON-NLS-1$
        try {
            p.requireLong("rev"); //$NON-NLS-1$
            fail("expected ToolParameterException for non-numeric string"); //$NON-NLS-1$
        } catch (ToolParameterException e) {
            // expected
        }
    }

    @Test
    public void requireLongRejectsBooleanValue() {
        ToolParameters p = new ToolParameters(Map.of("rev", true)); //$NON-NLS-1$
        try {
            p.requireLong("rev"); //$NON-NLS-1$
            fail("expected ToolParameterException for boolean"); //$NON-NLS-1$
        } catch (ToolParameterException e) {
            // expected
        }
    }

    // ---- optLong fallback semantics ----

    @Test
    public void optLongReturnsDefaultForFractional() {
        ToolParameters p = new ToolParameters(Map.of("rev", 1.5)); //$NON-NLS-1$
        assertEquals(-1L, p.optLong("rev", -1L)); //$NON-NLS-1$
    }

    @Test
    public void optLongReturnsDefaultForNaN() {
        ToolParameters p = new ToolParameters(Map.of("rev", Double.NaN)); //$NON-NLS-1$
        assertEquals(-1L, p.optLong("rev", -1L)); //$NON-NLS-1$
    }

    @Test
    public void optLongReturnsDefaultForInfinity() {
        ToolParameters p = new ToolParameters(Map.of("rev", Double.POSITIVE_INFINITY)); //$NON-NLS-1$
        assertEquals(-1L, p.optLong("rev", -1L)); //$NON-NLS-1$
    }

    @Test
    public void optLongAcceptsExactDoubleAsLong() {
        ToolParameters p = new ToolParameters(Map.of("rev", 42.0)); //$NON-NLS-1$
        assertEquals(42L, p.optLong("rev", -1L)); //$NON-NLS-1$
    }

    @Test
    public void optLongAcceptsInteger() {
        ToolParameters p = new ToolParameters(Map.of("rev", 42)); //$NON-NLS-1$
        assertEquals(42L, p.optLong("rev", -1L)); //$NON-NLS-1$
    }
}
