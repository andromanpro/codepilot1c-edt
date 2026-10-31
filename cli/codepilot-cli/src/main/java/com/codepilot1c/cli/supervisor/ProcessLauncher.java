/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.supervisor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Injectable process creation boundary. Arguments are passed without shell interpolation. */
@FunctionalInterface
public interface ProcessLauncher {
    ProcessHandleFacade start(List<String> command, Path standardOutput, Path standardError) throws IOException;
}
