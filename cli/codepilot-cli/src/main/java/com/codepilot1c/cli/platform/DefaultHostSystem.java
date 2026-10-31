/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/** Production host adapter backed only by Java SE APIs. */
public final class DefaultHostSystem implements HostSystem {
    @Override public String osName() { return System.getProperty("os.name", "unknown"); }
    @Override public String javaVersion() { return System.getProperty("java.version", "unknown"); }
    @Override public String userHome() { return System.getProperty("user.home", ""); }
    @Override public String environment(String name) { return System.getenv(name); }
    @Override public String systemProperty(String name) { return System.getProperty(name); }

    @Override public boolean isDirectory(String path) {
        try { return Files.isDirectory(Path.of(path)); } catch (InvalidPathException exception) { return false; }
    }

    @Override public boolean isRegularFile(String path) {
        try { return Files.isRegularFile(Path.of(path)); } catch (InvalidPathException exception) { return false; }
    }

    @Override public boolean isReadable(String path) {
        try { return Files.isReadable(Path.of(path)); } catch (InvalidPathException exception) { return false; }
    }

    @Override public List<String> children(String directory) {
        try (var paths = Files.list(Path.of(directory))) {
            return paths.map(Path::toString).sorted().toList();
        } catch (IOException | InvalidPathException exception) {
            return List.of();
        }
    }
}
