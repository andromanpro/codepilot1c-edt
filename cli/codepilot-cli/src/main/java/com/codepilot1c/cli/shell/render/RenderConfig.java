/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell.render;

import java.util.Objects;
import java.util.function.UnaryOperator;

/** Immutable renderer configuration supplied by the terminal-owning caller. */
public record RenderConfig(RenderMode mode, UnaryOperator<String> redactor) {
    public RenderConfig {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(redactor, "redactor");
    }

    public static RenderConfig ansi(UnaryOperator<String> redactor) {
        return new RenderConfig(RenderMode.ANSI, redactor);
    }

    public static RenderConfig plain(UnaryOperator<String> redactor) {
        return new RenderConfig(RenderMode.PLAIN, redactor);
    }

    /**
     * Resolves caller-observed terminal capabilities without reading environment
     * variables or system properties in the renderer.
     */
    public static RenderConfig forCapabilities(boolean ansiCapable, boolean noColor,
            boolean dumbTerminal, UnaryOperator<String> redactor) {
        RenderMode resolved = ansiCapable && !noColor && !dumbTerminal
                ? RenderMode.ANSI : RenderMode.PLAIN;
        return new RenderConfig(resolved, redactor);
    }

    String redact(String value) {
        return Objects.requireNonNull(redactor.apply(value), "redactor result");
    }
}
