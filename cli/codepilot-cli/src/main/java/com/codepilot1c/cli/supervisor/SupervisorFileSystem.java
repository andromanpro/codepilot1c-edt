/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.supervisor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Injectable filesystem boundary used by the EDT supervisor. */
public interface SupervisorFileSystem {
    Path canonicalDirectory(String value) throws IOException;
    boolean exists(Path path);
    void createDirectories(Path path) throws IOException;
    void writeAtomically(Path path, String content) throws IOException;
    String readString(Path path) throws IOException;
    List<Path> listJsonFiles(Path directory) throws IOException;
    void deleteIfExists(Path path) throws IOException;
}
