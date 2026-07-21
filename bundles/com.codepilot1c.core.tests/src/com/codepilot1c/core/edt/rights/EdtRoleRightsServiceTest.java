package com.codepilot1c.core.edt.rights;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.rights.model.Right;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

public class EdtRoleRightsServiceTest {

    @Test
    public void unsupportedExtensionConfigurationRightHasDedicatedCodeAndContext() {
        EdtRoleRightsService service = new EdtRoleRightsService();
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        configuration.setNamePrefix("ар_"); //$NON-NLS-1$

        MetadataOperationException exception = service.unsupportedRightException(
                "ДО.Артель", //$NON-NLS-1$
                "Role.ар_ОсновнаяРоль", //$NON-NLS-1$
                configuration,
                "Administration", //$NON-NLS-1$
                Set.<Right>of());

        assertEquals(MetadataOperationCode.UNSUPPORTED_IN_EXTENSION, exception.getCode());
        assertTrue(exception.getMessage().contains("\"error\":\"UNSUPPORTED_IN_EXTENSION\"")); //$NON-NLS-1$
        assertTrue(exception.getMessage().contains("\"project\":\"ДО.Артель\"")); //$NON-NLS-1$
        assertTrue(exception.getMessage().contains("\"role\":\"Role.ар_ОсновнаяРоль\"")); //$NON-NLS-1$
        assertTrue(exception.getMessage().contains("\"right\":\"Administration\"")); //$NON-NLS-1$
        assertTrue(exception.getMessage().contains("\"targetKind\":\"Configuration\"")); //$NON-NLS-1$
        assertTrue(exception.getMessage().contains("\"isExtensionProject\":true")); //$NON-NLS-1$
        assertTrue(exception.getMessage().contains("ThinClient")); //$NON-NLS-1$
    }

    @Test
    public void jsonEscapingHandlesControlCharacters() {
        String escaped = EdtRoleRightsService.escapeJson("Role.A\nB\r\"C"); //$NON-NLS-1$

        assertEquals("Role.A\\nB\\r\\\"C", escaped); //$NON-NLS-1$
    }

    @Test
    public void fallbackExtensionConfigRightsAreNotEmpty() {
        assertTrue(EdtRoleRightsService.fallbackExtensionConfigRights()
                .containsAll(List.of("ThinClient", "WebClient"))); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
