package com.codepilot1c.core.tools.surface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.junit.Test;

import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.file.EditFileTool;
import com.codepilot1c.core.tools.file.GlobTool;
import com.codepilot1c.core.tools.file.GrepTool;
import com.codepilot1c.core.tools.file.ListFilesTool;
import com.codepilot1c.core.tools.file.ReadFileTool;
import com.codepilot1c.core.tools.file.WriteTool;
import com.codepilot1c.core.tools.metadata.EdtValidateRequestTool;
import com.codepilot1c.core.tools.metadata.EnsureModuleArtifactTool;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ToolSurfaceSchemaParityTest {
    private static final Map<String, ITool> RAW_TOOLS = Map.of(
            "read_file", new ReadFileTool(), //$NON-NLS-1$
            "list_files", new ListFilesTool(), //$NON-NLS-1$
            "glob", new GlobTool(), //$NON-NLS-1$
            "grep", new GrepTool(), //$NON-NLS-1$
            "edit_file", new EditFileTool(), //$NON-NLS-1$
            "write_file", new WriteTool(), //$NON-NLS-1$
            "edt_validate_request", new EdtValidateRequestTool(), //$NON-NLS-1$
            "ensure_module_artifact", new EnsureModuleArtifactTool()); //$NON-NLS-1$

    @Test
    public void explicitOverridesPreservePrimaryPropertiesAndRequiredFields() {
        for (Map.Entry<String, ITool> entry : RAW_TOOLS.entrySet()) {
            JsonObject raw = parse(entry.getValue().getParameterSchema());
            JsonObject effective = parse(ToolSurfaceSchemaNormalizer.normalizeBuiltIn(
                    entry.getKey(), entry.getValue().getParameterSchema()));

            assertEquals(entry.getKey(), propertyNames(raw), propertyNames(effective));
            assertEquals(entry.getKey(), requiredNames(raw), requiredNames(effective));
            assertFalse(entry.getKey(), effective.get("additionalProperties").getAsBoolean()); //$NON-NLS-1$
        }
    }

    @Test
    public void validationOverrideListsEveryPublicRuntimeOperation() {
        JsonObject effective = parse(ToolSurfaceSchemaNormalizer.normalizeBuiltIn(
                "edt_validate_request", new EdtValidateRequestTool().getParameterSchema())); //$NON-NLS-1$
        assertEquals(runtimeOperationNames(), enumValues(effective, "operation")); //$NON-NLS-1$
    }

    @Test
    public void writeFileOverrideAdvertisesDocumentationCreation() {
        JsonObject effective = parse(ToolSurfaceSchemaNormalizer.normalizeBuiltIn(
                "write_file", new WriteTool().getParameterSchema())); //$NON-NLS-1$
        String pathDescription = effective.getAsJsonObject("properties") //$NON-NLS-1$
                .getAsJsonObject("path").get("description").getAsString(); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(pathDescription.contains("*.md")); //$NON-NLS-1$
        assertTrue(pathDescription.contains("*.txt")); //$NON-NLS-1$
    }

    @Test
    public void explicitNumericRangesMatchRuntimeLimits() {
        JsonObject read = parse(ToolSurfaceSchemaNormalizer.normalizeBuiltIn(
                "read_file", new ReadFileTool().getParameterSchema())); //$NON-NLS-1$
        assertEquals(1, property(read, "start_line").get("minimum").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, property(read, "end_line").get("minimum").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject glob = parse(ToolSurfaceSchemaNormalizer.normalizeBuiltIn(
                "glob", new GlobTool().getParameterSchema())); //$NON-NLS-1$
        assertEquals(500, property(glob, "max_results").get("maximum").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(property(glob, "max_results").has("minimum")); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject grep = parse(ToolSurfaceSchemaNormalizer.normalizeBuiltIn(
                "grep", new GrepTool().getParameterSchema())); //$NON-NLS-1$
        assertFalse(property(grep, "context_lines").has("minimum")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static JsonObject parse(String schema) {
        return JsonParser.parseString(schema).getAsJsonObject();
    }

    private static Set<String> propertyNames(JsonObject schema) {
        return new LinkedHashSet<>(schema.getAsJsonObject("properties").keySet()); //$NON-NLS-1$
    }

    private static JsonObject property(JsonObject schema, String name) {
        return schema.getAsJsonObject("properties").getAsJsonObject(name); //$NON-NLS-1$
    }

    private static Set<String> requiredNames(JsonObject schema) {
        if (!schema.has("required")) { //$NON-NLS-1$
            return Set.of();
        }
        return StreamSupport.stream(schema.getAsJsonArray("required").spliterator(), false) //$NON-NLS-1$
                .map(JsonElement::getAsString)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> enumValues(JsonObject schema, String property) {
        JsonArray values = schema.getAsJsonObject("properties") //$NON-NLS-1$
                .getAsJsonObject(property)
                .getAsJsonArray("enum"); //$NON-NLS-1$
        return StreamSupport.stream(values.spliterator(), false)
                .map(JsonElement::getAsString)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> runtimeOperationNames() {
        Set<String> names = Arrays.stream(ValidationOperation.values())
                .map(ValidationOperation::getToolName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        names.addAll(Set.of("external_manage", "extension_manage", "dcs_manage")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return names;
    }
}
