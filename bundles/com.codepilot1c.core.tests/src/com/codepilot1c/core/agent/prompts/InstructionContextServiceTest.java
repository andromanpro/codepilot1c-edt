package com.codepilot1c.core.agent.prompts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

public class InstructionContextServiceTest {

    @Test
    public void loadsLayeredAgentsAndCodeWithExpectedPrecedence() throws Exception {
        Path userHome = Files.createTempDirectory("instruction-home"); //$NON-NLS-1$
        Path projectRoot = Files.createTempDirectory("instruction-project"); //$NON-NLS-1$
        Path nestedProject = Files.createDirectories(projectRoot.resolve("module/submodule")); //$NON-NLS-1$

        Files.createDirectories(userHome.resolve(".codepilot")); //$NON-NLS-1$
        Files.writeString(userHome.resolve(".codepilot/AGENTS.md"), "user-agents"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.createDirectories(userHome.resolve(".codepilot1c")); //$NON-NLS-1$
        Files.writeString(userHome.resolve(".codepilot1c/AGENTS.md"), "user-agents-v2"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(projectRoot.resolve("AGENTS.md"), "root-agents"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(projectRoot.resolve("Code.md"), "root-code"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(nestedProject.resolve("Code.md"), "nested-code"); //$NON-NLS-1$ //$NON-NLS-2$

        InstructionContextService service = new InstructionContextService(nestedProject, userHome);

        List<InstructionContextService.InstructionLayer> agentsLayers = service.loadAgentsLayers();
        assertEquals(3, agentsLayers.size());
        assertEquals("user-agents", agentsLayers.get(0).content()); //$NON-NLS-1$
        assertEquals("user-agents-v2", agentsLayers.get(1).content()); //$NON-NLS-1$
        assertEquals("root-agents", agentsLayers.get(2).content()); //$NON-NLS-1$

        List<InstructionContextService.InstructionLayer> codeLayers =
                service.loadCodeLayers(false, nestedProject.toString());
        assertEquals(1, codeLayers.size());
        assertEquals("nested-code", codeLayers.get(0).content()); //$NON-NLS-1$
        assertEquals(InstructionContextService.LayerKind.CODE, codeLayers.get(0).kind());
        assertEquals("FOUND", codeLayers.get(0).status()); //$NON-NLS-1$
    }

    @Test
    public void loadCodeLayersSupportsCaseAliases() throws Exception {
        Path userHome = Files.createTempDirectory("instruction-home"); //$NON-NLS-1$
        Path projectRoot = Files.createTempDirectory("instruction-project"); //$NON-NLS-1$
        Files.writeString(projectRoot.resolve("CODE.md"), "upper-code"); //$NON-NLS-1$ //$NON-NLS-2$

        InstructionContextService service = new InstructionContextService(projectRoot, userHome);

        List<InstructionContextService.InstructionLayer> codeLayers =
                service.loadCodeLayers(false, projectRoot.toString());
        assertEquals(1, codeLayers.size());
        assertEquals("upper-code", codeLayers.get(0).content()); //$NON-NLS-1$
    }

    @Test
    public void missingCodeMdDoesNotReturnLayer() throws Exception {
        Path userHome = Files.createTempDirectory("instruction-home"); //$NON-NLS-1$
        Path projectRoot = Files.createTempDirectory("instruction-project"); //$NON-NLS-1$

        InstructionContextService service = new InstructionContextService(projectRoot, userHome);

        List<InstructionContextService.InstructionLayer> codeLayers =
                service.loadCodeLayers(false, projectRoot.toString());
        assertTrue(codeLayers.isEmpty());
    }

    @Test
    public void nullOrBlankProjectPathDoesNotFallbackToResolverStartForCodeLayers() throws Exception {
        Path userHome = Files.createTempDirectory("instruction-home"); //$NON-NLS-1$
        Path projectRoot = Files.createTempDirectory("instruction-project"); //$NON-NLS-1$
        Files.writeString(projectRoot.resolve("Code.md"), "resolver-code"); //$NON-NLS-1$ //$NON-NLS-2$

        InstructionContextService service = new InstructionContextService(projectRoot, userHome);

        assertTrue(service.loadCodeLayers(false).isEmpty());
        assertTrue(service.loadCodeLayers(false, null).isEmpty());
        assertTrue(service.loadCodeLayers(false, "   ").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void invalidProjectPathDoesNotFallbackToResolverStartForCodeLayers() throws Exception {
        Path userHome = Files.createTempDirectory("instruction-home"); //$NON-NLS-1$
        Path projectRoot = Files.createTempDirectory("instruction-project"); //$NON-NLS-1$
        Files.writeString(projectRoot.resolve("Code.md"), "resolver-code"); //$NON-NLS-1$ //$NON-NLS-2$

        InstructionContextService service = new InstructionContextService(projectRoot, userHome);

        assertTrue(service.loadCodeLayers(false, "\u0000").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void ancestorWalkDoesNotLoadParentInstructionsWhenStartIsProjectRoot() throws Exception {
        Path userHome = Files.createTempDirectory("instruction-home"); //$NON-NLS-1$
        Path parent = Files.createTempDirectory("instruction-parent"); //$NON-NLS-1$
        Path projectRoot = Files.createDirectories(parent.resolve("repo")); //$NON-NLS-1$
        Files.writeString(parent.resolve("AGENTS.md"), "parent-agents"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(projectRoot.resolve("AGENTS.md"), "project-agents"); //$NON-NLS-1$ //$NON-NLS-2$

        InstructionContextService service = new InstructionContextService(projectRoot, userHome);

        List<InstructionContextService.InstructionLayer> agentsLayers = service.loadAgentsLayers();
        assertEquals(1, agentsLayers.size());
        assertEquals("project-agents", agentsLayers.get(0).content()); //$NON-NLS-1$
    }
}
