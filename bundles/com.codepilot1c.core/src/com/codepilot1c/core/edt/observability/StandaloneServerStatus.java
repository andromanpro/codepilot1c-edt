package com.codepilot1c.core.edt.observability;

import java.util.List;

public record StandaloneServerStatus(
        String serverName,
        String state,
        long pid,
        List<Integer> ports,
        String configPath,
        String infobasePath,
        boolean debugSession,
        int breakpointsCount,
        boolean designerOrImportSession,
        List<OneCProcessSnapshot> relatedProcesses,
        List<InfobaseLockSnapshot> locks,
        List<String> lastErrors) {

    public StandaloneServerStatus {
        serverName = serverName == null ? "" : serverName; //$NON-NLS-1$
        state = state == null ? "unknown" : state; //$NON-NLS-1$
        ports = List.copyOf(ports == null ? List.of() : ports);
        configPath = configPath == null ? "" : configPath; //$NON-NLS-1$
        infobasePath = infobasePath == null ? "" : infobasePath; //$NON-NLS-1$
        relatedProcesses = List.copyOf(relatedProcesses == null ? List.of() : relatedProcesses);
        locks = List.copyOf(locks == null ? List.of() : locks);
        lastErrors = List.copyOf(lastErrors == null ? List.of() : lastErrors);
    }
}
