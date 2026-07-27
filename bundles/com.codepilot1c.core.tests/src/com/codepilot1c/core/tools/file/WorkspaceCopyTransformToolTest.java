package com.codepilot1c.core.tools.file;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.agent.profiles.BuildAgentProfile;
import com.codepilot1c.core.agent.profiles.ExtensionBuildProfile;
import com.codepilot1c.core.agent.profiles.MetadataBuildProfile;
import com.google.gson.JsonParser;

public class WorkspaceCopyTransformToolTest {

    @Test
    public void transformCountsPlainAndRegexReplacementsAndPreservesEol() {
        String source = "Вызов(аи_АртельБоты);\r\n"
                + "Константы.аи_АртельTenantID.Получить();\r\n"
                + "РегистрыСведений.аи_ПерсональныеБотыАртель.СоздатьНаборЗаписей();\r\n";

        WorkspaceCopyTransformSupport.TransformResult result = WorkspaceCopyTransformSupport.transform(source,
                List.of(new WorkspaceCopyTransformSupport.PlainReplacement("аи_АртельБоты", "ар_аи_АртельБоты"),
                        new WorkspaceCopyTransformSupport.PlainReplacement("Константы.аи_Артель",
                                "Константы.ар_аи_Артель")),
                List.of(new WorkspaceCopyTransformSupport.RegexReplacement("РегистрыСведений\\.аи_",
                        "РегистрыСведений.ар_аи_")),
                true);

        String expected = "Вызов(ар_аи_АртельБоты);\r\n"
                + "Константы.ар_аи_АртельTenantID.Получить();\r\n"
                + "РегистрыСведений.ар_аи_ПерсональныеБотыАртель.СоздатьНаборЗаписей();\r\n";

        assertEquals(expected, result.content());
        assertEquals(3, result.replacementCounts().size());
        assertEquals(1, result.replacementCounts().get(0).count());
        assertEquals(1, result.replacementCounts().get(1).count());
        assertEquals(1, result.replacementCounts().get(2).count());
    }

    @Test
    public void validationRejectsTraversalAndStructuredEdtTargetsByDefault() {
        assertFalse(WorkspaceCopyTransformSupport.validateWorkspacePath("../outside.bsl", true).ok());
        assertFalse(WorkspaceCopyTransformSupport.validateWorkspacePath("Project/src/Catalogs/Тест/Тест.mdo", true)
                .ok());
        assertFalse(WorkspaceCopyTransformSupport.validateWorkspacePath("Project/src/Form.form", true).ok());
        assertTrue(WorkspaceCopyTransformSupport.validateWorkspacePath("Project/src/CommonModules/Модуль/Module.bsl",
                true).ok());
        assertTrue(WorkspaceCopyTransformSupport.validateWorkspacePath("Project/docs/report.md", true).ok());
    }

    @Test
    public void schemasAreValidJsonAndDeclareRequiredFields() {
        assertRequired(new WorkspaceCopyTransformTool().getParameterSchema(), "source_path", "target_path");
        assertRequired(new WorkspaceCopyTransformBatchTool().getParameterSchema(), "operations");
    }

    @Test
    public void registeredToolsAreVisibleInMutatingProfiles() {
        assertEquals("workspace_copy_transform", new WorkspaceCopyTransformTool().getName());
        assertEquals("workspace_copy_transform_batch", new WorkspaceCopyTransformBatchTool().getName());

        assertTrue(new BuildAgentProfile().getAllowedTools().contains("workspace_copy_transform"));
        assertTrue(new BuildAgentProfile().getAllowedTools().contains("workspace_copy_transform_batch"));
        assertTrue(new MetadataBuildProfile().getAllowedTools().contains("workspace_copy_transform"));
        assertTrue(new ExtensionBuildProfile().getAllowedTools().contains("workspace_copy_transform_batch"));
    }

    private void assertRequired(String schema, String... requiredNames) {
        var parsed = JsonParser.parseString(schema).getAsJsonObject();
        var required = parsed.getAsJsonArray("required");
        assertNotNull(required);
        for (String name : requiredNames) {
            assertTrue("missing required " + name, required.contains(JsonParser.parseString("\"" + name + "\"")));
        }
    }
}
