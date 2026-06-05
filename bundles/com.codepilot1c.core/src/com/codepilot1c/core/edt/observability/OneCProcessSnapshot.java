package com.codepilot1c.core.edt.observability;

import java.util.List;

public record OneCProcessSnapshot(
        long pid,
        long ppid,
        String user,
        String processName,
        String commandLine,
        String processType,
        List<String> infobasePaths,
        List<Integer> ports,
        List<Long> children) {

    public OneCProcessSnapshot {
        user = user == null ? "" : user; //$NON-NLS-1$
        processName = processName == null ? "" : processName; //$NON-NLS-1$
        commandLine = commandLine == null ? "" : commandLine; //$NON-NLS-1$
        processType = processType == null ? "unknown" : processType; //$NON-NLS-1$
        infobasePaths = List.copyOf(infobasePaths == null ? List.of() : infobasePaths);
        ports = List.copyOf(ports == null ? List.of() : ports);
        children = List.copyOf(children == null ? List.of() : children);
    }
}
