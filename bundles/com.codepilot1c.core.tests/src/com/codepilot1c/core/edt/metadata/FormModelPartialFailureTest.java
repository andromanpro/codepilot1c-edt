package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class FormModelPartialFailureTest {

    @Test
    public void updateFormModelCountsModelOperationsOnly() throws Exception {
        String source = Files.readString(locateServiceSource());
        int start = source.indexOf("public UpdateFormModelResult updateFormModel"); //$NON-NLS-1$
        int end = source.indexOf("private StubPhaseOutcome writeHandlerStubsDetailed", start); //$NON-NLS-1$
        assertTrue("updateFormModel end marker not found", end > start); //$NON-NLS-1$
        String method = source.substring(start, end);

        assertFalse(method.contains("operationSummaries.addAll(")); //$NON-NLS-1$
        assertFalse(method.contains("new ArrayList<>(operationSummaries)")); //$NON-NLS-1$
    }

    private static Path locateServiceSource() {
        Path moduleRelative = Path.of("..", "com.codepilot1c.core", "src", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "com", "codepilot1c", "core", "edt", "metadata", "EdtMetadataService.java"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        if (Files.isRegularFile(moduleRelative)) {
            return moduleRelative;
        }
        Path reactorRelative = Path.of("bundles", "com.codepilot1c.core", "src", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "com", "codepilot1c", "core", "edt", "metadata", "EdtMetadataService.java"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        if (Files.isRegularFile(reactorRelative)) {
            return reactorRelative;
        }
        throw new AssertionError("Cannot locate EdtMetadataService.java from " //$NON-NLS-1$
                + Path.of("").toAbsolutePath()); //$NON-NLS-1$
    }
}
