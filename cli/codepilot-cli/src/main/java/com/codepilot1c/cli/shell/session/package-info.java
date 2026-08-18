/* SPDX-License-Identifier: AGPL-3.0-only */
/**
 * Secure, provider-neutral persistence for CLI interactive sessions.
 *
 * <p>Each {@link com.codepilot1c.cli.shell.session.SessionStore} instance
 * serializes its own lifecycle operations. Sharing the same directory between
 * independently constructed stores is supported for reading, but cross-process
 * write coordination is intentionally outside this package's contract.</p>
 */
package com.codepilot1c.cli.shell.session;
