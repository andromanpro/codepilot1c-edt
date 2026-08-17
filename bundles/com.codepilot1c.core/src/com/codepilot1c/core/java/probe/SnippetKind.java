package com.codepilot1c.core.java.probe;

import java.util.Locale;

/** Supported compile-only snippet wrappers. */
public enum SnippetKind {
    AUTO,
    EXPRESSION,
    STATEMENTS,
    DECLARATION,
    COMPILATION_UNIT;

    public static SnippetKind parse(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
