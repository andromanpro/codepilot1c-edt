/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.platform;

import java.util.List;

/** Injectable view of the host environment and filesystem. */
public interface HostSystem {
    String osName();
    String javaVersion();
    String userHome();
    String environment(String name);
    String systemProperty(String name);
    boolean isDirectory(String path);
    boolean isRegularFile(String path);
    boolean isReadable(String path);
    List<String> children(String directory);
}
