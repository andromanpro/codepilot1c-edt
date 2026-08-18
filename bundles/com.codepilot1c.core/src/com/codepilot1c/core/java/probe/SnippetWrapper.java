package com.codepilot1c.core.java.probe;

import java.util.List;

/** Builds deterministic source wrappers without interpreting snippet text. */
public final class SnippetWrapper {

    public static final List<SnippetKind> AUTO_ORDER = List.of(
            SnippetKind.EXPRESSION,
            SnippetKind.STATEMENTS,
            SnippetKind.DECLARATION,
            SnippetKind.COMPILATION_UNIT);

    private SnippetWrapper() {
    }

    public static WrappedSnippet wrap(String snippet, SnippetKind kind) {
        return switch (kind) {
            case EXPRESSION -> new WrappedSnippet(
                    "final class Probe { Object __p() throws Throwable { return (\n" //$NON-NLS-1$
                            + snippet + "\n); } }\n", //$NON-NLS-1$
                    1);
            case STATEMENTS -> new WrappedSnippet(
                    "final class Probe { void __p() throws Throwable {\n" //$NON-NLS-1$
                            + snippet + "\n} }\n", //$NON-NLS-1$
                    1);
            case DECLARATION -> new WrappedSnippet(
                    "final class Probe {\n" + snippet + "\n}\n", //$NON-NLS-1$ //$NON-NLS-2$
                    1);
            case COMPILATION_UNIT -> new WrappedSnippet(snippet, 0);
            case AUTO -> throw new IllegalArgumentException("AUTO must be resolved before wrapping"); //$NON-NLS-1$
        };
    }

    public record WrappedSnippet(String source, int preludeLines) {
    }
}
