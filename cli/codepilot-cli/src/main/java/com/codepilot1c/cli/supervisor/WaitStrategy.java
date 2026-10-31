/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.supervisor;

import java.time.Duration;

/** Injectable polling delay; tests can advance a fake clock without sleeping. */
@FunctionalInterface
public interface WaitStrategy {
    void pause(Duration duration) throws InterruptedException;

    static WaitStrategy threadSleep() {
        return duration -> Thread.sleep(Math.max(1L, duration.toMillis()));
    }
}
