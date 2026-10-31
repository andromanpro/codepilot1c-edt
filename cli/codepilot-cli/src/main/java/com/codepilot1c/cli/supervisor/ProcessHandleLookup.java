/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.supervisor;

import java.util.Optional;

/** Injectable lookup boundary for registry PIDs. */
@FunctionalInterface
public interface ProcessHandleLookup {
    Optional<ProcessHandleFacade> find(long pid);
}
