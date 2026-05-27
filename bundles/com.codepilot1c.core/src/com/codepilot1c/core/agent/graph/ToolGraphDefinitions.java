package com.codepilot1c.core.agent.graph;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Built-in tool graph definitions.
 */
final class ToolGraphDefinitions {

    private ToolGraphDefinitions() {
    }

    static ToolGraph createGeneralGraph() {
        ToolNode general = ToolNode.builder("general") //$NON-NLS-1$
                .restrictive(false)
                .maxVisits(50)
                .build();

        Map<String, ToolNode> nodes = new HashMap<>();
        nodes.put(general.getId(), general);

        return new ToolGraph(
                ToolGraphRegistry.GENERAL_GRAPH_ID,
                "General", //$NON-NLS-1$
                "1", //$NON-NLS-1$
                general.getId(),
                nodes,
                List.of(new ToolEdge(general.getId(), general.getId(), EdgePredicates.always(), 0))
        );
    }

    static ToolGraph createBslGraph() {
        Set<String> inspectTools = Set.of(
                "read_file", "list_files", "edit_file", "write_file", "grep", "glob", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "bsl_symbol_at_position", "bsl_type_at_position", "bsl_scope_members", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "bsl_list_methods", "bsl_get_method_body", "bsl_analyze_method", "bsl_module_context", "bsl_module_exports", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "edt_find_references", "edt_content_assist", //$NON-NLS-1$ //$NON-NLS-2$
                "edt_metadata_details", "scan_metadata_index", "inspect_platform_reference", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "get_diagnostics" //$NON-NLS-1$
        );

        ToolNode inspect = ToolNode.builder("bsl_inspect") //$NON-NLS-1$
                .allowTools(inspectTools)
                .allowTools(orchestrationTools())
                .allowTool("edt_validate_request") //$NON-NLS-1$
                .maxVisits(20)
                .build();

        ToolNode validated = ToolNode.builder("bsl_validated") //$NON-NLS-1$
                .allowTools(inspectTools)
                .allowTools(orchestrationTools())
                .allowTool("ensure_module_artifact") //$NON-NLS-1$
                .allowTool("edt_validate_request") //$NON-NLS-1$
                .maxVisits(20)
                .build();

        Map<String, ToolNode> nodes = new HashMap<>();
        nodes.put(inspect.getId(), inspect);
        nodes.put(validated.getId(), validated);

        return new ToolGraph(
                ToolGraphRegistry.BSL_GRAPH_ID,
                "BSL", //$NON-NLS-1$
                "1", //$NON-NLS-1$
                inspect.getId(),
                nodes,
                List.of(new ToolEdge(inspect.getId(), validated.getId(),
                        EdgePredicates.and(
                                EdgePredicates.toolNameIs("edt_validate_request"), //$NON-NLS-1$
                                EdgePredicates.success()),
                        10))
        );
    }

    static ToolGraph createMetadataGraph() {
        Set<String> inspectTools = Set.of(
                "scan_metadata_index", "edt_metadata_details", "edt_field_type_candidates", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "inspect_platform_reference", "edt_find_references" //$NON-NLS-1$ //$NON-NLS-2$
        );
        Set<String> mutateTools = Set.of(
                "create_metadata", "add_metadata_child", "update_metadata", "delete_metadata", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "author_yaxunit_tests" //$NON-NLS-1$
        );
        Set<String> diagTools = Set.of("get_diagnostics", "edt_diagnostics"); //$NON-NLS-1$ //$NON-NLS-2$

        ToolNode inspect = ToolNode.builder("metadata_inspect") //$NON-NLS-1$
                .allowTools(inspectTools)
                .allowTools(orchestrationTools())
                .allowTool("edt_validate_request") //$NON-NLS-1$
                .maxVisits(10)
                .build();

        ToolNode mutate = ToolNode.builder("metadata_mutate") //$NON-NLS-1$
                .allowTools(mutateTools)
                .allowTools(orchestrationTools())
                .allowTool("ensure_module_artifact") //$NON-NLS-1$
                .allowTool("edt_validate_request") //$NON-NLS-1$
                .maxVisits(10)
                .build();

        ToolNode moduleEdit = ToolNode.builder("metadata_module_edit") //$NON-NLS-1$
                .allowTools(inspectTools)
                .allowTools(orchestrationTools())
                .allowTool("read_file") //$NON-NLS-1$
                .allowTool("edit_file") //$NON-NLS-1$
                .allowTool("write_file") //$NON-NLS-1$
                .allowTool("grep") //$NON-NLS-1$
                .allowTool("glob") //$NON-NLS-1$
                .allowTool("get_diagnostics") //$NON-NLS-1$
                .maxVisits(10)
                .build();

        ToolNode diagnostics = ToolNode.builder("metadata_diagnostics") //$NON-NLS-1$
                .allowTools(diagTools)
                .allowTools(inspectTools)
                .allowTools(orchestrationTools())
                .maxVisits(10)
                .build();

        Map<String, ToolNode> nodes = new HashMap<>();
        nodes.put(inspect.getId(), inspect);
        nodes.put(mutate.getId(), mutate);
        nodes.put(moduleEdit.getId(), moduleEdit);
        nodes.put(diagnostics.getId(), diagnostics);

        List<ToolEdge> edges = List.of(
                new ToolEdge(inspect.getId(), mutate.getId(),
                        EdgePredicates.and(
                                EdgePredicates.toolNameIs("edt_validate_request"), //$NON-NLS-1$
                                EdgePredicates.success()),
                        10),
                new ToolEdge(mutate.getId(), moduleEdit.getId(),
                        EdgePredicates.and(
                                EdgePredicates.toolNameIs("ensure_module_artifact"), //$NON-NLS-1$
                                EdgePredicates.success()),
                        10),
                new ToolEdge(mutate.getId(), diagnostics.getId(),
                        EdgePredicates.and(
                                EdgePredicates.toolNameIn(mutateTools),
                                EdgePredicates.success()),
                        10)
        );

        return new ToolGraph(
                ToolGraphRegistry.METADATA_GRAPH_ID,
                "Metadata", //$NON-NLS-1$
                "1", //$NON-NLS-1$
                inspect.getId(),
                nodes,
                edges
        );
    }

    static ToolGraph createFormsGraph() {
        Set<String> inspectTools = Set.of(
                "inspect_form_layout", "edt_metadata_details", "scan_metadata_index" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        );
        Set<String> mutateTools = Set.of(
                "create_form", "apply_form_recipe", "mutate_form_model" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        );
        Set<String> diagTools = Set.of("get_diagnostics"); //$NON-NLS-1$

        ToolNode inspect = ToolNode.builder("form_inspect") //$NON-NLS-1$
                .allowTools(inspectTools)
                .allowTools(orchestrationTools())
                .allowTool("edt_validate_request") //$NON-NLS-1$
                .maxVisits(10)
                .build();

        ToolNode mutate = ToolNode.builder("form_mutate") //$NON-NLS-1$
                .allowTools(mutateTools)
                .allowTools(orchestrationTools())
                .allowTool("edt_validate_request") //$NON-NLS-1$
                .maxVisits(10)
                .build();

        ToolNode diagnostics = ToolNode.builder("form_diagnostics") //$NON-NLS-1$
                .allowTools(diagTools)
                .allowTools(inspectTools)
                .allowTools(orchestrationTools())
                .maxVisits(10)
                .build();

        Map<String, ToolNode> nodes = new HashMap<>();
        nodes.put(inspect.getId(), inspect);
        nodes.put(mutate.getId(), mutate);
        nodes.put(diagnostics.getId(), diagnostics);

        List<ToolEdge> edges = List.of(
                new ToolEdge(inspect.getId(), mutate.getId(),
                        EdgePredicates.and(
                                EdgePredicates.toolNameIs("edt_validate_request"), //$NON-NLS-1$
                                EdgePredicates.success()),
                        10),
                new ToolEdge(mutate.getId(), diagnostics.getId(),
                        EdgePredicates.and(
                                EdgePredicates.toolNameIn(mutateTools),
                                EdgePredicates.success()),
                        10)
        );

        return new ToolGraph(
                ToolGraphRegistry.FORMS_GRAPH_ID,
                "Forms", //$NON-NLS-1$
                "1", //$NON-NLS-1$
                inspect.getId(),
                nodes,
                edges
        );
    }

    static ToolGraph createDcsGraph() {
        Set<String> contextTools = Set.of(
                "dcs_manage", "dcs_get_summary", "dcs_list_nodes", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "scan_metadata_index", "edt_metadata_details", "inspect_platform_reference" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        );
        Set<String> dcsMutationTools = Set.of(
                "dcs_manage", "dcs_create_main_schema", "dcs_upsert_query_dataset", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "dcs_upsert_parameter", "dcs_upsert_calculated_field" //$NON-NLS-1$ //$NON-NLS-2$
        );
        Set<String> diagTools = Set.of("get_diagnostics", "edt_diagnostics", "analyze_tool_error"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        ToolNode context = ToolNode.builder("dcs_context_summary_list_read") //$NON-NLS-1$
                .allowTools(contextTools)
                .allowTools(orchestrationTools())
                .allowTool("edt_validate_request") //$NON-NLS-1$
                .promptHint("Use dcs_manage only for get_summary/list_nodes in this node; validate before mutating commands.") //$NON-NLS-1$
                .maxVisits(10)
                .build();

        ToolNode mutate = ToolNode.builder("dcs_mutate_after_edt_validate_request") //$NON-NLS-1$
                .allowTools(contextTools)
                .allowTools(dcsMutationTools)
                .allowTools(orchestrationTools())
                .allowTool("edt_validate_request") //$NON-NLS-1$
                .promptHint("Command-level DCS validation is approximated at tool-name level; mutating dcs_manage commands require validation_token.") //$NON-NLS-1$
                .maxVisits(10)
                .build();

        ToolNode diagnostics = ToolNode.builder("dcs_diagnostics_after_dcs_manage_success") //$NON-NLS-1$
                .allowTools(diagTools)
                .allowTools(contextTools)
                .allowTools(orchestrationTools())
                .maxVisits(10)
                .build();

        Map<String, ToolNode> nodes = new HashMap<>();
        nodes.put(context.getId(), context);
        nodes.put(mutate.getId(), mutate);
        nodes.put(diagnostics.getId(), diagnostics);

        List<ToolEdge> edges = List.of(
                new ToolEdge(context.getId(), mutate.getId(),
                        EdgePredicates.and(
                                EdgePredicates.toolNameIs("edt_validate_request"), //$NON-NLS-1$
                                EdgePredicates.success()),
                        10),
                new ToolEdge(mutate.getId(), diagnostics.getId(),
                        EdgePredicates.and(
                                EdgePredicates.toolNameIn(dcsMutationTools),
                                EdgePredicates.success()),
                        10)
        );

        return new ToolGraph(
                ToolGraphRegistry.DCS_GRAPH_ID,
                "DCS", //$NON-NLS-1$
                "1", //$NON-NLS-1$
                context.getId(),
                nodes,
                edges
        );
    }

    static ToolGraph createFeatureGraph() {
        Set<String> inspectTools = Set.of(
                "scan_metadata_index", "edt_metadata_details", "edt_field_type_candidates", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "inspect_platform_reference", "inspect_form_layout", "dcs_manage", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "dcs_get_summary", "dcs_list_nodes", "qa_inspect" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        );
        Set<String> metadataTools = Set.of(
                "create_metadata", "add_metadata_child", "update_metadata", "delete_metadata", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "ensure_module_artifact" //$NON-NLS-1$
        );
        Set<String> implementationTools = Set.of(
                "create_form", "apply_form_recipe", "mutate_form_model", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "ensure_module_artifact", "dcs_manage", "dcs_create_main_schema", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "dcs_upsert_query_dataset", "dcs_upsert_parameter", "dcs_upsert_calculated_field" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        );
        Set<String> moduleEditTools = Set.of(
                "read_file", "edit_file", "grep", "glob", "get_diagnostics" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        );
        Set<String> diagQaTools = Set.of(
                "get_diagnostics", "edt_diagnostics", "analyze_tool_error", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "qa_inspect", "qa_generate" //$NON-NLS-1$ //$NON-NLS-2$
        );

        ToolNode inspect = ToolNode.builder("feature_inspect_plan") //$NON-NLS-1$
                .allowTools(inspectTools)
                .allowTools(orchestrationTools())
                .allowTool("edt_validate_request") //$NON-NLS-1$
                .maxVisits(15)
                .build();

        ToolNode metadata = ToolNode.builder("feature_metadata_after_validation") //$NON-NLS-1$
                .allowTools(inspectTools)
                .allowTools(metadataTools)
                .allowTools(orchestrationTools())
                .allowTool("edt_validate_request") //$NON-NLS-1$
                .maxVisits(15)
                .build();

        ToolNode implementation = ToolNode.builder("feature_forms_modules_dcs_after_metadata") //$NON-NLS-1$
                .allowTools(inspectTools)
                .allowTools(implementationTools)
                .allowTools(orchestrationTools())
                .allowTool("edt_validate_request") //$NON-NLS-1$
                .maxVisits(20)
                .build();

        ToolNode moduleEdit = ToolNode.builder("feature_module_edit_after_ensure") //$NON-NLS-1$
                .allowTools(inspectTools)
                .allowTools(moduleEditTools)
                .allowTools(orchestrationTools())
                .allowTool("edt_validate_request") //$NON-NLS-1$
                .promptHint("Only edit prepared BSL module artifacts in this node; structured EDT artifacts remain blocked by file tools.") //$NON-NLS-1$
                .maxVisits(10)
                .build();

        ToolNode diagnostics = ToolNode.builder("feature_diagnostics_qa") //$NON-NLS-1$
                .allowTools(inspectTools)
                .allowTools(diagQaTools)
                .allowTools(orchestrationTools())
                .allowTool("edt_validate_request") //$NON-NLS-1$
                .maxVisits(15)
                .build();

        Map<String, ToolNode> nodes = new HashMap<>();
        nodes.put(inspect.getId(), inspect);
        nodes.put(metadata.getId(), metadata);
        nodes.put(implementation.getId(), implementation);
        nodes.put(moduleEdit.getId(), moduleEdit);
        nodes.put(diagnostics.getId(), diagnostics);

        Set<String> semanticMutationTools = Set.of(
                "create_metadata", "add_metadata_child", "update_metadata", "delete_metadata", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "create_form", "apply_form_recipe", "mutate_form_model", "ensure_module_artifact", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "dcs_manage", "dcs_create_main_schema", "dcs_upsert_query_dataset", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "dcs_upsert_parameter", "dcs_upsert_calculated_field" //$NON-NLS-1$ //$NON-NLS-2$
        );

        List<ToolEdge> edges = List.of(
                new ToolEdge(inspect.getId(), metadata.getId(),
                        EdgePredicates.and(
                                EdgePredicates.toolNameIs("edt_validate_request"), //$NON-NLS-1$
                                EdgePredicates.success()),
                        10),
                new ToolEdge(metadata.getId(), implementation.getId(),
                        EdgePredicates.and(
                                EdgePredicates.toolNameIn(metadataTools),
                                EdgePredicates.success()),
                        10),
                new ToolEdge(metadata.getId(), moduleEdit.getId(),
                        EdgePredicates.and(
                                EdgePredicates.toolNameIs("ensure_module_artifact"), //$NON-NLS-1$
                                EdgePredicates.success()),
                        20),
                new ToolEdge(implementation.getId(), moduleEdit.getId(),
                        EdgePredicates.and(
                                EdgePredicates.toolNameIs("ensure_module_artifact"), //$NON-NLS-1$
                                EdgePredicates.success()),
                        20),
                new ToolEdge(implementation.getId(), diagnostics.getId(),
                        EdgePredicates.and(
                                EdgePredicates.toolNameIn(semanticMutationTools),
                                EdgePredicates.success()),
                        10),
                new ToolEdge(moduleEdit.getId(), diagnostics.getId(),
                        EdgePredicates.and(
                                EdgePredicates.toolNameIs("edit_file"), //$NON-NLS-1$
                                EdgePredicates.success()),
                        10),
                new ToolEdge(metadata.getId(), diagnostics.getId(),
                        EdgePredicates.and(
                                EdgePredicates.toolNameIn(semanticMutationTools),
                                EdgePredicates.success()),
                        1)
        );

        return new ToolGraph(
                ToolGraphRegistry.FEATURE_GRAPH_ID,
                "Feature", //$NON-NLS-1$
                "1", //$NON-NLS-1$
                inspect.getId(),
                nodes,
                edges
        );
    }

    private static Set<String> orchestrationTools() {
        return Set.of(
                "delegate_to_agent", //$NON-NLS-1$
                "task" //$NON-NLS-1$
        );
    }
}
