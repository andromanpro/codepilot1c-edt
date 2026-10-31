package com.codepilot1c.core.mcp.host.discovery;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/** Filesystem boundary for the local MCP host instance registry. */
public interface InstanceRegistryFileSystem {

    void writeAtomically(Path target, String json) throws IOException;

    Optional<String> read(Path target) throws IOException;

    boolean deleteIfOwned(Path target, String instanceId, long pid, String nonce) throws IOException;
}
