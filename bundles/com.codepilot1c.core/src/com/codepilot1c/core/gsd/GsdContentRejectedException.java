/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

import java.util.List;

/**
 * Thrown when a GSD content-security scan rejects user-supplied text because it
 * contains blocked injection patterns, exceeds content caps, or otherwise fails
 * the {@link GsdContentSecurity} policy.
 *
 * <p>The exception message is safe for tool output: it never contains the raw
 * rejected input. Callers should surface the message to the agent but must
 * <em>not</em> echo the original text back.</p>
 */
public final class GsdContentRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String fieldName;
    private final List<String> reasons;

    /**
     * @param fieldName the logical field that was rejected (e.g. "goal", "summary")
     * @param reasons   human-readable rejection reasons (never empty)
     */
    public GsdContentRejectedException(String fieldName, List<String> reasons) {
        super(buildMessage(fieldName, reasons));
        this.fieldName = fieldName;
        this.reasons = reasons != null ? List.copyOf(reasons) : List.of();
    }

    /** @return the logical field name that was rejected */
    public String getFieldName() {
        return fieldName;
    }

    /** @return immutable list of rejection reasons */
    public List<String> getReasons() {
        return reasons;
    }

    private static String buildMessage(String fieldName, List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return "content rejected for field '" + fieldName + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "content rejected for field '" + fieldName + "': " + String.join("; ", reasons); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
