/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.Test;

public class ShadedJarContentIT {
    @Test public void shadedJarPreservesJlineClassesNativeLibrariesAndProviders() throws IOException {
        Path shadedJar = Path.of(System.getProperty("shaded.jar"));
        try (ZipFile jar = new ZipFile(shadedJar.toFile())) {
            assertEntry(jar, "org/jline/terminal/Terminal.class");
            assertEntry(jar, "org/jline/reader/LineReader.class");
            assertEntry(jar, "org/jline/terminal/impl/jni/JniTerminalProvider.class");

            assertEntry(jar, "org/jline/nativ/Linux/x86_64/libjlinenative.so");
            assertEntry(jar, "org/jline/nativ/Mac/arm64/libjlinenative.jnilib");
            assertEntry(jar, "org/jline/nativ/Windows/x86_64/jlinenative.dll");

            assertProvider(jar, "META-INF/services/org/jline/terminal/provider/exec",
                    "org.jline.terminal.impl.exec.ExecTerminalProvider");
            assertProvider(jar, "META-INF/services/org/jline/terminal/provider/jni",
                    "org.jline.terminal.impl.jni.JniTerminalProvider");
        }
    }

    private static void assertEntry(ZipFile jar, String path) {
        assertNotNull("missing shaded entry " + path, jar.getEntry(path));
    }

    private static void assertProvider(ZipFile jar, String path, String implementation) throws IOException {
        ZipEntry entry = jar.getEntry(path);
        assertNotNull("missing provider metadata " + path, entry);
        String content = new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
        assertTrue("missing provider " + implementation, content.contains("class = " + implementation));
    }
}
