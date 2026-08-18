/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell.render;

/** Receives ordered text deltas and owns their end/cancellation boundary. */
public interface StreamingTextSink extends AutoCloseable {
    void append(String delta);

    /** Flushes buffered Markdown state and completes the stream. */
    void end();

    /** Flushes buffered text safely and completes a cancelled stream. */
    void cancel();

    boolean isFinished();

    @Override
    default void close() {
        end();
    }
}
