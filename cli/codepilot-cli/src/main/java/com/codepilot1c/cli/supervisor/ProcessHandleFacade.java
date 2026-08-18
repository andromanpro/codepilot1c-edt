/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.supervisor;

import java.util.Optional;

/** Small injectable view of a launched or discovered operating-system process. */
public interface ProcessHandleFacade {
    long pid();
    boolean isAlive();
    boolean destroy();
    boolean destroyForcibly();
    Optional<String> commandLine();
}
