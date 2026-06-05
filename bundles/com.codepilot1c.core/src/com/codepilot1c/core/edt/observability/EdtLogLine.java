package com.codepilot1c.core.edt.observability;

import java.nio.file.Path;

public record EdtLogLine(
        String source,
        Path path,
        int lineNumber,
        String text,
        String timestamp,
        String level,
        String opId,
        long pid,
        String infobase) {

    public EdtLogLine {
        source = source == null ? "" : source; //$NON-NLS-1$
        text = text == null ? "" : text; //$NON-NLS-1$
        timestamp = timestamp == null ? "" : timestamp; //$NON-NLS-1$
        level = level == null ? "" : level; //$NON-NLS-1$
        opId = opId == null ? "" : opId; //$NON-NLS-1$
        infobase = infobase == null ? "" : infobase; //$NON-NLS-1$
    }
}
