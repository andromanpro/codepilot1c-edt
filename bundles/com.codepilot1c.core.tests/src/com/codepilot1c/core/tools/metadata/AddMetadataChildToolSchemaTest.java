package com.codepilot1c.core.tools.metadata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AddMetadataChildToolSchemaTest {

    @Test
    public void schemaAllowsBatchChildrenWithoutTopLevelName() {
        String schema = new AddMetadataChildTool().getParameterSchema();

        assertTrue(schema.contains("children")); //$NON-NLS-1$
        assertTrue(schema.contains("\"validation_token\"")); //$NON-NLS-1$
        assertFalse("Batch child creation must not require top-level name", //$NON-NLS-1$
                schema.contains("\"required\": [\"project\", \"parent_fqn\", \"child_kind\", \"name\", \"validation_token\"]")); //$NON-NLS-1$
    }
}
