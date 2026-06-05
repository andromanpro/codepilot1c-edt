package com.codepilot1c.core.edt.observability;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class EdtObservabilityGateway {

    public List<ProcessHandle> allProcesses() {
        try (Stream<ProcessHandle> processes = ProcessHandle.allProcesses()) {
            return processes.toList();
        }
    }

    public Optional<ProcessHandle> process(long pid) {
        return ProcessHandle.of(pid);
    }

    public boolean exists(Path path) {
        return path != null && Files.exists(path);
    }

    public boolean isDirectory(Path path) {
        return path != null && Files.isDirectory(path);
    }
}
