/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.discovery;

import java.util.Objects;

/** A validated EDT Eclipse home and its launcher. */
public record EdtInstallation(String home, String launcher, String source, LauncherKind kind) {
    public EdtInstallation {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(launcher, "launcher");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(kind, "kind");
    }

    /** Source-compatible constructor for callers written before launcher kinds were distinguished. */
    public EdtInstallation(String home, String launcher, String source) {
        this(home, launcher, source, LauncherKind.ECLIPSE);
    }

    /** Whether this launcher can host the CodePilot headless Equinox application. */
    public boolean supportsHeadlessApplication() { return kind == LauncherKind.ECLIPSE; }
}
