package com.codepilot1c.core.agent.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.codepilot1c.core.agent.AgentConfig;
import com.codepilot1c.core.tools.ToolResult;

public class ToolGraphHarnessDefinitionsTest {

    @Test
    public void registryContainsDcsAndFeatureGraphs() {
        ToolGraphRegistry registry = ToolGraphRegistry.getInstance();

        assertNotNull(registry.get(ToolGraphRegistry.DCS_GRAPH_ID));
        assertNotNull(registry.get(ToolGraphRegistry.FEATURE_GRAPH_ID));
    }

    @Test
    public void dcsGraphAllowsContextThenValidationThenDiagnostics() {
        ToolGraphRouter router = ToolGraphTestSupport.createRouter();
        router.initialize("graph=dcs", AgentConfig.defaults()); //$NON-NLS-1$

        ToolGraphToolFilter context = router.buildToolFilter();
        assertTrue(context.allows("dcs_manage")); //$NON-NLS-1$
        assertTrue(context.allows("edt_validate_request")); //$NON-NLS-1$
        assertFalse(context.allows("create_metadata")); //$NON-NLS-1$
        assertFalse(context.allows("create_form")); //$NON-NLS-1$

        router.onToolResult("edt_validate_request", ToolResult.success("ok")); //$NON-NLS-1$ //$NON-NLS-2$
        ToolGraphToolFilter validated = router.buildToolFilter();
        assertTrue(validated.allows("dcs_manage")); //$NON-NLS-1$
        assertFalse(validated.allows("get_diagnostics")); //$NON-NLS-1$

        router.onToolResult("dcs_manage", ToolResult.success("ok")); //$NON-NLS-1$ //$NON-NLS-2$
        ToolGraphToolFilter diagnostics = router.buildToolFilter();
        assertTrue(diagnostics.allows("get_diagnostics")); //$NON-NLS-1$
        assertTrue(diagnostics.allows("edt_diagnostics")); //$NON-NLS-1$
        assertFalse(diagnostics.allows("write_file")); //$NON-NLS-1$
    }

    @Test
    public void featureGraphExposesSemanticEdtFlowWithoutArbitraryFileTools() {
        ToolGraphRouter router = ToolGraphTestSupport.createRouter();
        router.initialize("graph=feature", AgentConfig.defaults()); //$NON-NLS-1$

        ToolGraphToolFilter inspect = router.buildToolFilter();
        assertTrue(inspect.allows("scan_metadata_index")); //$NON-NLS-1$
        assertTrue(inspect.allows("edt_metadata_details")); //$NON-NLS-1$
        assertTrue(inspect.allows("edt_validate_request")); //$NON-NLS-1$
        assertFalse(inspect.allows("write_file")); //$NON-NLS-1$

        router.onToolResult("edt_validate_request", ToolResult.success("ok")); //$NON-NLS-1$ //$NON-NLS-2$
        ToolGraphToolFilter metadata = router.buildToolFilter();
        assertTrue(metadata.allows("create_metadata")); //$NON-NLS-1$
        assertTrue(metadata.allows("add_metadata_child")); //$NON-NLS-1$
        assertTrue(metadata.allows("ensure_module_artifact")); //$NON-NLS-1$
        assertFalse(metadata.allows("write_file")); //$NON-NLS-1$

        router.onToolResult("create_metadata", ToolResult.success("ok")); //$NON-NLS-1$ //$NON-NLS-2$
        ToolGraphToolFilter implementation = router.buildToolFilter();
        assertFalse(implementation.allows("create_form")); //$NON-NLS-1$
        assertTrue(implementation.allows("edt_validate_request")); //$NON-NLS-1$

        router.onToolResult("edt_validate_request", ToolResult.success("ok")); //$NON-NLS-1$ //$NON-NLS-2$
        implementation = router.buildToolFilter();
        assertTrue(implementation.allows("create_form")); //$NON-NLS-1$
        assertTrue(implementation.allows("mutate_form_model")); //$NON-NLS-1$
        assertTrue(implementation.allows("dcs_manage")); //$NON-NLS-1$
        assertTrue(implementation.allows("ensure_module_artifact")); //$NON-NLS-1$
        assertFalse(implementation.allows("write_file")); //$NON-NLS-1$

        router.onToolResult("ensure_module_artifact", ToolResult.success("ok")); //$NON-NLS-1$ //$NON-NLS-2$
        ToolGraphToolFilter moduleEdit = router.buildToolFilter();
        assertTrue(moduleEdit.allows("edit_file")); //$NON-NLS-1$
        assertTrue(moduleEdit.allows("read_file")); //$NON-NLS-1$
        assertFalse(moduleEdit.allows("write_file")); //$NON-NLS-1$

        router.onToolResult("edit_file", ToolResult.success("ok")); //$NON-NLS-1$ //$NON-NLS-2$
        ToolGraphToolFilter afterModuleEdit = router.buildToolFilter();
        assertTrue(afterModuleEdit.allows("get_diagnostics")); //$NON-NLS-1$

        router = ToolGraphTestSupport.createRouter();
        router.initialize("graph=feature", AgentConfig.defaults()); //$NON-NLS-1$
        router.onToolResult("edt_validate_request", ToolResult.success("ok")); //$NON-NLS-1$ //$NON-NLS-2$
        router.onToolResult("create_metadata", ToolResult.success("ok")); //$NON-NLS-1$ //$NON-NLS-2$
        router.onToolResult("edt_validate_request", ToolResult.success("ok")); //$NON-NLS-1$ //$NON-NLS-2$
        router.onToolResult("dcs_manage", ToolResult.success("ok")); //$NON-NLS-1$ //$NON-NLS-2$
        ToolGraphToolFilter diagnostics = router.buildToolFilter();
        assertTrue(diagnostics.allows("get_diagnostics")); //$NON-NLS-1$
        assertTrue(diagnostics.allows("qa_inspect")); //$NON-NLS-1$
        assertTrue(diagnostics.allows("qa_generate")); //$NON-NLS-1$
    }

    @Test
    public void keywordSelectionRoutesReportOnlyToDcsAndMultiDomainFeatureToFeature() {
        KeywordToolGraphSelectionStrategy strategy = new KeywordToolGraphSelectionStrategy();

        assertEquals(ToolGraphRegistry.DCS_GRAPH_ID,
                strategy.selectGraphId("Сделай отчет по остаткам на дату через СКД")); //$NON-NLS-1$
        assertEquals(ToolGraphRegistry.FEATURE_GRAPH_ID,
                strategy.selectGraphId("Сделай учет товаров: справочник Номенклатура, документ Поступление, регистр, форма и отчет")); //$NON-NLS-1$
        assertEquals(ToolGraphRegistry.FORMS_GRAPH_ID,
                strategy.selectGraphId("Добавь управляемую форму элемента справочника")); //$NON-NLS-1$
        assertEquals(ToolGraphRegistry.BSL_GRAPH_ID,
                strategy.selectGraphId("Исправь процедуру в общем модуле BSL")); //$NON-NLS-1$
    }
}
