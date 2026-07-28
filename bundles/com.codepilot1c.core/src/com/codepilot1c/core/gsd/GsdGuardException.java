/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

import java.util.Collections;
import java.util.List;

/**
 * Raised when a {@link GsdState} violates one or more {@link GsdGuard} invariants.
 * Carries the full list of violations so callers can report them atomically.
 */
public class GsdGuardException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<String> violations;

    /**
     * Constructor for a single violation.
     *
     * @param message the violation message
     */
    public GsdGuardException(String message) {
        super(message);
        this.violations = List.of(message);
    }

    /**
     * Constructor for multiple violations.
     *
     * @param message    summary message
     * @param violations the individual violations
     */
    public GsdGuardException(String message, List<String> violations) {
        super(message);
        this.violations = violations == null ? List.of() : List.copyOf(violations);
    }

    /**
     * Returns the individual violations, in detection order.
     *
     * @return the unmodifiable violation list (never {@code null})
     */
    public List<String> getViolations() {
        return Collections.unmodifiableList(violations);
    }
}