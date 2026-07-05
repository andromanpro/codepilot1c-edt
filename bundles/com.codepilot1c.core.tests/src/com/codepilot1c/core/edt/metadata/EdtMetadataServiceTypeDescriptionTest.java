package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class EdtMetadataServiceTypeDescriptionTest {

    @Test
    public void createMetadataSupportsConstantTypeDescriptionProperty() throws Exception {
        String source = readCoreSource(
                "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java"); //$NON-NLS-1$

        assertTrue(
                "create_metadata(kind=Constant, properties.type=...) must build a TypeDescription instead of rejecting containment reference 'type'", //$NON-NLS-1$
                source.contains("applyTypeDescriptionProperty") //$NON-NLS-1$
                        || source.contains("setValueTypeDescription") //$NON-NLS-1$
                        || source.contains("setTypeDescriptionProperty")); //$NON-NLS-1$
    }

    @Test
    public void updateMetadataSupportsCommonCommandCommandParameterTypeDescription() throws Exception {
        String source = readCoreSource(
                "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java"); //$NON-NLS-1$

        assertTrue(
                "update_metadata must support CommonCommand.commandParameterType TypeDescription instead of generic containment rejection", //$NON-NLS-1$
                source.contains("commandParameterType") //$NON-NLS-1$
                        && (source.contains("applyTypeDescriptionProperty") //$NON-NLS-1$
                                || source.contains("setTypeDescriptionProperty"))); //$NON-NLS-1$
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
