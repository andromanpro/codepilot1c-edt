package com.codepilot1c.core.mcp.host.discovery;

/** Injectable process-id source so registry lifecycle tests do not depend on the test JVM. */
@FunctionalInterface
public interface PidSource {

    long currentPid();
}
