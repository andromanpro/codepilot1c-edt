package com.codepilot1c.core.edt.observability;

import java.util.List;

public record InfobaseLockSnapshot(
        String input,
        String normalizedPath,
        List<String> inspectedPaths,
        String lockKind,
        double confidence,
        List<String> evidence,
        List<Long> pids,
        List<OneCProcessSnapshot> processes) {

    public InfobaseLockSnapshot {
        input = input == null ? "" : input; //$NON-NLS-1$
        normalizedPath = normalizedPath == null ? "" : normalizedPath; //$NON-NLS-1$
        inspectedPaths = List.copyOf(inspectedPaths == null ? List.of() : inspectedPaths);
        lockKind = lockKind == null ? "unknown" : lockKind; //$NON-NLS-1$
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
        pids = List.copyOf(pids == null ? List.of() : pids);
        processes = List.copyOf(processes == null ? List.of() : processes);
    }
}
