/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.supervisor;

import java.time.Instant;
import java.util.Optional;

/** Small injectable view of a launched or discovered operating-system process. */
public interface ProcessHandleFacade {
    long pid();
    boolean isAlive();
    boolean destroy();
    boolean destroyForcibly();
    Optional<String> commandLine();

    /**
     * When the operating system says this PID started, or empty when it will not say.
     *
     * <p>This is the PID-recycling defence: a PID can be reused by an unrelated program, but the
     * start instant of that reused PID differs from the one observed at launch.</p>
     */
    Optional<Instant> startInstant();
}
