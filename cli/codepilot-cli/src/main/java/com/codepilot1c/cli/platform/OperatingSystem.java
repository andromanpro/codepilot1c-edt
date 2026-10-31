/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.platform;

import java.util.Locale;

/** Supported host operating-system families. */
public enum OperatingSystem {
    MACOS("/"), LINUX("/"), WINDOWS("\\"), OTHER("/");

    private final String separator;

    OperatingSystem(String separator) { this.separator = separator; }
    public String separator() { return separator; }

    public static OperatingSystem from(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (normalized.contains("mac") || normalized.contains("darwin")) return MACOS;
        if (normalized.contains("win")) return WINDOWS;
        if (normalized.contains("linux")) return LINUX;
        return OTHER;
    }
}
