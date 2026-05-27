package com.codepilot1c.core.tools.file;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StructuredEdtArtifactGuardTest {

    @Test
    public void detectsDcsAndTemplateArtifactsAsStructuredEdtArtifacts() {
        assertTrue(WriteTool.isStructuredEdtArtifactPath(
                "Configuration/src/Reports/ОстаткиТоваров/Ext/MainDataCompositionSchema.xml")); //$NON-NLS-1$
        assertTrue(WriteTool.isStructuredEdtArtifactPath(
                "Configuration/src/Reports/ОстаткиТоваров/Templates/MainDataCompositionSchema/MainDataCompositionSchema.mxl")); //$NON-NLS-1$
        assertTrue(EditFileTool.isStructuredEdtArtifactPath(
                "Configuration/src/Documents/ПродажаТоваров/Forms/ФормаДокумента/Form.form")); //$NON-NLS-1$
    }

    @Test
    public void allowsRegularModulesAndProjectMemoryFile() {
        assertFalse(WriteTool.isStructuredEdtArtifactPath(
                "Configuration/src/Documents/ПродажаТоваров/ObjectModule.bsl")); //$NON-NLS-1$
        assertFalse(EditFileTool.isStructuredEdtArtifactPath("Configuration/Code.md")); //$NON-NLS-1$
    }
}
