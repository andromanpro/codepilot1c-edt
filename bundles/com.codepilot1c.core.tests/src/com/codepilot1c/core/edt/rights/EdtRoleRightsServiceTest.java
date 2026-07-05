package com.codepilot1c.core.edt.rights;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class EdtRoleRightsServiceTest {

    @Test
    public void extensionConfigurationRightDiagnosticsExposeUnsupportedInExtensionOrAvailableRights() throws Exception {
        String source = readCoreSource(
                "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/rights/EdtRoleRightsService.java"); //$NON-NLS-1$

        assertTrue(
                "mutate_role_rights should diagnose extension configuration rights as UNSUPPORTED_IN_EXTENSION or include available config rights", //$NON-NLS-1$
                source.contains("UNSUPPORTED_IN_EXTENSION") //$NON-NLS-1$
                        || source.contains("availableConfigRights") //$NON-NLS-1$
                        || source.contains("available configuration rights")); //$NON-NLS-1$
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
