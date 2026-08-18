/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.supervisor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Java ProcessBuilder/ProcessHandle adapters for production supervision. */
public final class DefaultProcessAccess implements ProcessLauncher, ProcessHandleLookup {
    @Override public ProcessHandleFacade start(List<String> command, Path stdout, Path stderr) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(stdout.toFile()));
        builder.redirectError(ProcessBuilder.Redirect.appendTo(stderr.toFile()));
        return new JavaProcessHandle(builder.start().toHandle());
    }

    @Override public Optional<ProcessHandleFacade> find(long pid) {
        return ProcessHandle.of(pid).map(JavaProcessHandle::new);
    }

    private record JavaProcessHandle(ProcessHandle delegate) implements ProcessHandleFacade {
        @Override public long pid() { return delegate.pid(); }
        @Override public boolean isAlive() { return delegate.isAlive(); }
        @Override public boolean destroy() { return delegate.destroy(); }
        @Override public boolean destroyForcibly() { return delegate.destroyForcibly(); }
        @Override public Optional<String> commandLine() { return delegate.info().commandLine(); }
    }
}
