/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.discovery;

import java.util.Objects;

/** A validated EDT Eclipse home and its launcher. */
public record EdtInstallation(String home, String launcher, String source) {
    public EdtInstallation {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(launcher, "launcher");
        Objects.requireNonNull(source, "source");
    }
}
