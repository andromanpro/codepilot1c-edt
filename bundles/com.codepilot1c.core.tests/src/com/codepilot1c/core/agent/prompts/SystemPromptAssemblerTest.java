package com.codepilot1c.core.agent.prompts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.skills.SkillCatalog;

public class SystemPromptAssemblerTest {

    @Test
    public void assembledPromptIncludesLayeredContextAndRequestedSkillsWithProvenance() throws Exception {
        Path userHome = Files.createTempDirectory("assembler-home"); //$NON-NLS-1$
        Path projectRoot = Files.createTempDirectory("assembler-project"); //$NON-NLS-1$
        Files.createDirectories(userHome.resolve(".codepilot")); //$NON-NLS-1$
        Files.writeString(userHome.resolve(".codepilot/AGENTS.md"), "user-agents-layer"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(projectRoot.resolve("AGENTS.md"), "project-agents-layer"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(projectRoot.resolve("Code.md"), "project-code-layer"); //$NON-NLS-1$ //$NON-NLS-2$

        SystemPromptAssembler assembler = new SystemPromptAssembler(
                new InstructionContextService(projectRoot, userHome),
                new SkillCatalog(projectRoot, userHome));

        SystemPromptAssembler.PromptAssembly nonBackend = assembler.assembleDetailed(
                "BASE", "BASE", "build", List.of("review", "explain"), false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals(1, countOccurrences(nonBackend.prompt(), "BASE")); //$NON-NLS-1$
        assertTrue(nonBackend.prompt().contains("Source: " + userHome.resolve(".codepilot/AGENTS.md").toRealPath())); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(nonBackend.prompt().contains("project-agents-layer")); //$NON-NLS-1$
        assertTrue(nonBackend.prompt().contains("Skill: review")); //$NON-NLS-1$
        assertTrue(!nonBackend.prompt().contains("Skill: explain")); //$NON-NLS-1$

        SystemPromptAssembler.PromptAssembly backend = assembler.assembleDetailed(
                new SystemPromptAssembler.AssemblyInput(
                        "BASE", null, "build", List.of("review", "explain"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                        projectRoot.toString(), "session-1"), //$NON-NLS-1$
                true);

        assertTrue(backend.prompt().contains("project-code-layer")); //$NON-NLS-1$
        assertTrue(backend.prompt().contains("Skill: explain")); //$NON-NLS-1$
    }

    @Test
    public void promptProviderUsesSameAssemblyPathAsDirectAssembler() {
        String assembled = SystemPromptAssembler.getInstance().assemble(
                AgentPromptTemplates.buildBuildPrompt(),
                null,
                "build", //$NON-NLS-1$
                List.of());
        String fromProvider = new com.codepilot1c.core.mcp.host.prompt.PromptTemplateProvider()
                .getPrompt("build", java.util.Map.of()) //$NON-NLS-1$
                .orElseThrow()
                .getMessages()
                .get(0)
                .getContent()
                .getText();

        assertEquals(assembled, fromProvider);
    }

    @Test
    public void assemblyUsesRuntimeProjectPathForInstructionLayers() throws Exception {
        Path userHome = Files.createTempDirectory("assembler-home"); //$NON-NLS-1$
        Path wrongStart = Files.createTempDirectory("assembler-wrong-start"); //$NON-NLS-1$
        Path projectRoot = Files.createTempDirectory("assembler-project"); //$NON-NLS-1$
        Files.writeString(projectRoot.resolve("Code.md"), "runtime-project-code"); //$NON-NLS-1$ //$NON-NLS-2$

        SystemPromptAssembler assembler = new SystemPromptAssembler(
                new InstructionContextService(wrongStart, userHome),
                new SkillCatalog(wrongStart, userHome));

        SystemPromptAssembler.PromptAssembly assembly = assembler.assembleDetailed(
                new SystemPromptAssembler.AssemblyInput(
                        "BASE", null, "build", List.of(), projectRoot.toString(), "session-1"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                false);

        assertTrue(assembly.prompt().contains("runtime-project-code")); //$NON-NLS-1$
    }

    @Test
    public void assemblerDoesNotLoadCodeLayerFromResolverStartWithoutProjectPath() throws Exception {
        Path userHome = Files.createTempDirectory("assembler-home"); //$NON-NLS-1$
        Path wrongStart = Files.createTempDirectory("assembler-wrong-start"); //$NON-NLS-1$
        Files.writeString(wrongStart.resolve("Code.md"), "wrong-code"); //$NON-NLS-1$ //$NON-NLS-2$

        SystemPromptAssembler assembler = new SystemPromptAssembler(
                new InstructionContextService(wrongStart, userHome),
                new SkillCatalog(wrongStart, userHome));

        SystemPromptAssembler.PromptAssembly assembly = assembler.assembleDetailed(
                new SystemPromptAssembler.AssemblyInput(
                        "BASE", null, "build", List.of(), null, "session-1"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                false);

        assertTrue(assembly.codeLayers().isEmpty());
        assertFalse(assembly.prompt().contains("Layered Context: Code.md")); //$NON-NLS-1$
        assertFalse(assembly.prompt().contains("wrong-code")); //$NON-NLS-1$
    }

    @Test
    public void assemblerIncludesSelectedProjectCodeViaProjectMemoryService() throws Exception {
        Path userHome = Files.createTempDirectory("assembler-home"); //$NON-NLS-1$
        Path wrongStart = Files.createTempDirectory("assembler-wrong-start"); //$NON-NLS-1$
        Path projectRoot = Files.createTempDirectory("assembler-project"); //$NON-NLS-1$
        Files.writeString(wrongStart.resolve("Code.md"), "wrong-code"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(projectRoot.resolve("code.md"), "selected-project-code"); //$NON-NLS-1$ //$NON-NLS-2$

        SystemPromptAssembler assembler = new SystemPromptAssembler(
                new InstructionContextService(wrongStart, userHome),
                new SkillCatalog(wrongStart, userHome));

        SystemPromptAssembler.PromptAssembly assembly = assembler.assembleDetailed(
                new SystemPromptAssembler.AssemblyInput(
                        "BASE", null, "build", List.of(), projectRoot.toString(), "session-1"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                false);

        assertTrue(assembly.prompt().contains("Layered Context: Code.md")); //$NON-NLS-1$
        assertTrue(assembly.prompt().contains("selected-project-code")); //$NON-NLS-1$
        assertTrue(assembly.prompt().contains("Status: FOUND")); //$NON-NLS-1$
        assertFalse(assembly.prompt().contains("wrong-code")); //$NON-NLS-1$
    }

    @Test
    public void largeCodeMdTruncationWarningAppearsInAssembledPrompt() throws Exception {
        Path userHome = Files.createTempDirectory("assembler-home"); //$NON-NLS-1$
        Path projectRoot = Files.createTempDirectory("assembler-project"); //$NON-NLS-1$
        Files.writeString(projectRoot.resolve("Code.md"), "x".repeat(80_000)); //$NON-NLS-1$ //$NON-NLS-2$

        SystemPromptAssembler assembler = new SystemPromptAssembler(
                new InstructionContextService(projectRoot, userHome),
                new SkillCatalog(projectRoot, userHome));

        SystemPromptAssembler.PromptAssembly assembly = assembler.assembleDetailed(
                new SystemPromptAssembler.AssemblyInput(
                        "BASE", null, "build", List.of(), projectRoot.toString(), "session-1"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                false);

        assertTrue(assembly.prompt().contains("Status: TRUNCATED")); //$NON-NLS-1$
        assertTrue(assembly.prompt().contains("Warning: Project memory content truncated to prompt byte budget")); //$NON-NLS-1$
    }

    @Test
    public void currentUserQueryReachesPromptContributor() throws Exception {
        Path userHome = Files.createTempDirectory("assembler-home"); //$NON-NLS-1$
        Path projectRoot = Files.createTempDirectory("assembler-project"); //$NON-NLS-1$
        SystemPromptAssembler assembler = new SystemPromptAssembler(
                new InstructionContextService(projectRoot, userHome),
                new SkillCatalog(projectRoot, userHome),
                ctx -> "\n\n## Test Query\n\n" + ctx.currentUserQuery() + "\n"); //$NON-NLS-1$ //$NON-NLS-2$

        SystemPromptAssembler.PromptAssembly assembly = assembler.assembleDetailed(
                new SystemPromptAssembler.AssemblyInput(
                        "BASE", null, "build", List.of(), projectRoot.toString(), "session-1", "needle query"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                false);

        assertTrue(assembly.prompt().contains("needle query")); //$NON-NLS-1$
    }

    @Test
    public void missingCodeMdDoesNotAddCodeLayer() throws Exception {
        Path userHome = Files.createTempDirectory("assembler-home"); //$NON-NLS-1$
        Path projectRoot = Files.createTempDirectory("assembler-project"); //$NON-NLS-1$

        SystemPromptAssembler assembler = new SystemPromptAssembler(
                new InstructionContextService(projectRoot, userHome),
                new SkillCatalog(projectRoot, userHome));

        SystemPromptAssembler.PromptAssembly assembly = assembler.assembleDetailed(
                new SystemPromptAssembler.AssemblyInput(
                        "BASE", null, "build", List.of(), projectRoot.toString(), "session-1"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                false);

        assertTrue(assembly.codeLayers().isEmpty());
        assertFalse(assembly.prompt().contains("Layered Context: Code.md")); //$NON-NLS-1$
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
