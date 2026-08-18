/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.config;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** Resolves the optional configuration location without any host-framework API. */
public final class PortableConfigPath {
    private PortableConfigPath() {
    }

    /** Resolves the standard configuration file for the current process. */
    public static Path systemDefault() {
        return resolve(System.getProperty("os.name", ""), System.getenv(), //$NON-NLS-1$ //$NON-NLS-2$
                System.getProperty("user.home", ".")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Resolves an OS-specific default. Public for deterministic host tests.
     * The Windows branch deliberately constructs backslash-separated input;
     * on Windows this is a normal {@link Path}, while tests on other hosts can
     * still verify that no Unix path convention leaked into the contract.
     */
    public static Path resolve(String osName, Map<String, String> environment, String userHome) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT); //$NON-NLS-1$
        if (os.contains("win")) { //$NON-NLS-1$
            String appData = nonBlank(environment.get("APPDATA")); //$NON-NLS-1$
            if (appData == null) {
                String profile = nonBlank(environment.get("USERPROFILE")); //$NON-NLS-1$
                appData = profile == null ? userHome + "\\AppData\\Roaming" : profile + "\\AppData\\Roaming"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            return Path.of(appData + "\\CodePilot\\runtime.properties"); //$NON-NLS-1$
        }
        if (os.contains("mac")) { //$NON-NLS-1$
            return Path.of(userHome, "Library", "Application Support", "CodePilot", "runtime.properties"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        String xdg = nonBlank(environment.get("XDG_CONFIG_HOME")); //$NON-NLS-1$
        return xdg == null
                ? Path.of(userHome, ".config", "codepilot", "runtime.properties") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                : Path.of(xdg, "codepilot", "runtime.properties"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String nonBlank(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }
}
