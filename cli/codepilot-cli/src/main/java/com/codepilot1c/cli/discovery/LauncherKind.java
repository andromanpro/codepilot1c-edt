/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.discovery;

/**
 * Which executable an EDT installation exposes, because the two are not interchangeable.
 *
 * <p>{@link #ECLIPSE} is the Equinox RCP launcher ({@code 1cedt} / {@code 1cedt.exe}); it accepts
 * {@code -nosplash}, {@code -application} and {@code -data}, so it can host the CodePilot headless
 * application. {@link #CLI_WRAPPER} is 1C's own front end ({@code 1cedtcli}), whose entire option
 * set is {@code -data -timeout -command -file -nl -v -ini-file -vmargs}. It has no
 * {@code -application}, rejects {@code -nosplash} with "Unrecognized option", and otherwise drops
 * into the interactive EDT shell, so it can never start an arbitrary Equinox application.</p>
 */
public enum LauncherKind {
    ECLIPSE("eclipse"),
    CLI_WRAPPER("cli_wrapper");

    private final String token;

    LauncherKind(String token) { this.token = token; }

    /** Stable, non-localized identifier safe to publish in CLI JSON output. */
    public String token() { return token; }
}
