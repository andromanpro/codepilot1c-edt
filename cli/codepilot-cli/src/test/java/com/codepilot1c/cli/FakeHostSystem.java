/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.codepilot1c.cli.platform.HostSystem;

final class FakeHostSystem implements HostSystem {
    String os = "Linux";
    String java = "17.0.12";
    String home = "/home/test";
    final Map<String, String> environment = new HashMap<>();
    final Map<String, String> properties = new HashMap<>();
    final Set<String> directories = new HashSet<>();
    final Set<String> files = new HashSet<>();
    final Set<String> unreadable = new HashSet<>();
    final Map<String, List<String>> children = new HashMap<>();

    void directory(String path, String... childPaths) {
        directories.add(path);
        children.put(path, List.of(childPaths));
        for (String child : childPaths) directories.add(child);
    }

    void file(String path) { files.add(path); }

    @Override public String osName() { return os; }
    @Override public String javaVersion() { return java; }
    @Override public String userHome() { return home; }
    @Override public String environment(String name) { return environment.get(name); }
    @Override public String systemProperty(String name) { return properties.get(name); }
    @Override public boolean isDirectory(String path) { return directories.contains(path); }
    @Override public boolean isRegularFile(String path) { return files.contains(path); }
    @Override public boolean isReadable(String path) { return files.contains(path) && !unreadable.contains(path); }
    @Override public List<String> children(String directory) {
        return new ArrayList<>(children.getOrDefault(directory, List.of()));
    }
}
