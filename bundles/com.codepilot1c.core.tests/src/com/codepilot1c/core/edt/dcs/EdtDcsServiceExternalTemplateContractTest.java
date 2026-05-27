package com.codepilot1c.core.edt.dcs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class EdtDcsServiceExternalTemplateContractTest {

    @Test
    public void createSchemaDoesNotPersistEmbeddedDcsThroughTemplateReference() throws Exception {
        String source = Files.readString(servicePath());

        assertFalse(source.contains("template.setTemplate(schema)")); //$NON-NLS-1$
        assertFalse(source.contains("DcsFactory.eINSTANCE.createDataCompositionSchema()")); //$NON-NLS-1$
    }

    @Test
    public void serviceUsesExternalTemplateDcsArtifact() throws Exception {
        String source = Files.readString(servicePath());

        assertTrue(source.contains("Template.dcs")); //$NON-NLS-1$
        assertTrue(source.contains("http://g5.1c.ru/v8/dt/data-composition-system/schema")); //$NON-NLS-1$
    }

    private static Path servicePath() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath(); //$NON-NLS-1$
        while (current != null) {
            Path candidate = current.resolve(
                    "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/dcs/EdtDcsService.java"); //$NON-NLS-1$
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("EdtDcsService.java not found from working directory"); //$NON-NLS-1$
    }
}
