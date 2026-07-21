package com.codepilot1c.core.tools.surface;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.Test;

public class QwenRuntimeReferenceGuardTest {
    private static final List<String> FORBIDDEN = List.of(
            "QwenFunction" + "CallingTransport", //$NON-NLS-1$ //$NON-NLS-2$
            "QwenToolCall" + "Examples", //$NON-NLS-1$ //$NON-NLS-2$
            "QwenContent" + "ToolCallParser", //$NON-NLS-1$ //$NON-NLS-2$
            "QwenStreaming" + "ToolCallParser", //$NON-NLS-1$ //$NON-NLS-2$
            "isQwen" + "Native", //$NON-NLS-1$ //$NON-NLS-2$
            "getResolved" + "ModelFamily", //$NON-NLS-1$ //$NON-NLS-2$
            "resolve" + "ModelFamily", //$NON-NLS-1$ //$NON-NLS-2$
            "BackendTool" + "Surface", //$NON-NLS-1$ //$NON-NLS-2$
            "Backend" + " note:", //$NON-NLS-1$ //$NON-NLS-2$
            "ProviderContext" + "Resolver", //$NON-NLS-1$ //$NON-NLS-2$
            "backend" + "Optimizations", //$NON-NLS-1$ //$NON-NLS-2$
            "supportsBackend" + "Optimizations"); //$NON-NLS-1$ //$NON-NLS-2$

    @Test
    public void liveRuntimeAndInstructionsDoNotReferenceRemovedApis() throws Exception {
        Path root = repositoryRoot();
        List<Path> files = new ArrayList<>();
        collectJava(files, root.resolve("bundles/com.codepilot1c.core/src")); //$NON-NLS-1$
        collectJava(files, root.resolve("bundles/com.codepilot1c.core.tests/src")); //$NON-NLS-1$
        collectJava(files, root.resolve("bundles/com.codepilot1c.ui/src")); //$NON-NLS-1$
        files.add(root.resolve("tools/generate_tool_prompt_inventory.py")); //$NON-NLS-1$
        if (Files.isRegularFile(root.resolve("AGENTS.md"))) { //$NON-NLS-1$
            files.add(root.resolve("AGENTS.md")); //$NON-NLS-1$
        }

        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            for (String forbidden : FORBIDDEN) {
                if (content.contains(forbidden)) {
                    violations.add(root.relativize(file) + " -> " + forbidden); //$NON-NLS-1$
                }
            }
        }
        assertTrue(String.join("\n", violations), violations.isEmpty()); //$NON-NLS-1$

        String chatView = Files.readString(
                root.resolve("bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views/ChatView.java"), //$NON-NLS-1$
                StandardCharsets.UTF_8);
        assertTrue(chatView.contains("ToolRegistry.getInstance().getToolDefinitions()")); //$NON-NLS-1$
        assertFalse(chatView.contains("ProviderSelectionGate")); //$NON-NLS-1$
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath(); //$NON-NLS-1$
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("pom.xml")) //$NON-NLS-1$
                    && Files.isDirectory(candidate.resolve("bundles/com.codepilot1c.core"))) { //$NON-NLS-1$
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Repository root not found"); //$NON-NLS-1$
    }

    private static void collectJava(List<Path> files, Path directory) throws IOException {
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java")) //$NON-NLS-1$
                    .forEach(files::add);
        }
    }
}
