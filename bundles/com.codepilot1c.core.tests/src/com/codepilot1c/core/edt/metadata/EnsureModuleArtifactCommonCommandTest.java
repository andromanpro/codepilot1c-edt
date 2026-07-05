package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;

public class EnsureModuleArtifactCommonCommandTest {

    private final MetadataRequestValidationService validation = new MetadataRequestValidationService();

    @Test
    public void commandModuleKindAliasIsAccepted() {
        Map<String, Object> payload;
        try {
            payload = validation.normalizeEnsureModuleArtifactPayload(
                    "Demo", //$NON-NLS-1$
                    "CommonCommand.аи_ОтправитьНаАнализИИ", //$NON-NLS-1$
                    "command", //$NON-NLS-1$
                    Boolean.TRUE,
                    null);
        } catch (MetadataOperationException e) {
            fail("module_kind=command should be supported for CommonCommand: " + e.getMessage()); //$NON-NLS-1$
            return;
        }

        assertEquals("COMMAND", payload.get("module_kind")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void autoForCommonCommandResolvesToCommandModule() {
        Map<String, Object> payload = validation.normalizeEnsureModuleArtifactPayload(
                "Demo", //$NON-NLS-1$
                "CommonCommand.аи_ОтправитьНаАнализИИ", //$NON-NLS-1$
                null,
                Boolean.TRUE,
                null);

        assertEquals("COMMAND", payload.get("module_kind")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void commandModuleArtifactPathUsesCommandModuleBsl() throws Exception {
        String source = readCoreSource(
                "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java"); //$NON-NLS-1$

        assertTrue(
                "CommonCommand module artifacts must be CommandModule.bsl, not Module.bsl/ObjectModule.bsl", //$NON-NLS-1$
                source.contains("CommandModule.bsl")); //$NON-NLS-1$
    }

    private String readCoreSource(String relativePath) throws Exception {
        Path repoRoot = findRepoRoot();
        return Files.readString(repoRoot.resolve(relativePath), StandardCharsets.UTF_8);
    }

    private Path findRepoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(); //$NON-NLS-1$
        while (current != null) {
            if (Files.isDirectory(current.resolve("bundles")) && Files.isDirectory(current.resolve(".planning"))) { //$NON-NLS-1$ //$NON-NLS-2$
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root"); //$NON-NLS-1$
    }
}
