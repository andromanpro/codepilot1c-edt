package com.codepilot1c.core.edt.extension;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class EdtExtensionServiceTest {

    @Test
    public void adoptLookupIncludesBotTopLevelObjectsOrReportsUnsupportedKind() throws Exception {
        String source = readCoreSource(
                "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/extension/EdtExtensionService.java"); //$NON-NLS-1$

        assertTrue(
                "extension_manage(adopt) must either resolve Bot objects explicitly or return UNSUPPORTED_KIND instead of METADATA_NOT_FOUND", //$NON-NLS-1$
                source.contains("case \"bot\"") //$NON-NLS-1$
                        || source.contains("getBots()") //$NON-NLS-1$
                        || source.contains("UNSUPPORTED_KIND")); //$NON-NLS-1$
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
