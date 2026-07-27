package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.junit.Test;

import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.tools.metadata.EdtValidateRequestTool;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class EdtValidateRequestToolSchemaTest {

    @Test
    public void schemaListsEveryPublicValidationOperation() {
        JsonObject schema = JsonParser.parseString(new EdtValidateRequestTool().getParameterSchema())
                .getAsJsonObject();
        JsonArray values = schema.getAsJsonObject("properties") //$NON-NLS-1$
                .getAsJsonObject("operation") //$NON-NLS-1$
                .getAsJsonArray("enum"); //$NON-NLS-1$
        Set<String> actual = StreamSupport.stream(values.spliterator(), false)
                .map(JsonElement::getAsString)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertEquals(runtimeOperationNames(), actual);
        assertTrue(new EdtValidateRequestTool().getDescription().contains("template")); //$NON-NLS-1$
    }

    private static Set<String> runtimeOperationNames() {
        Set<String> names = Arrays.stream(ValidationOperation.values())
                .map(ValidationOperation::getToolName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        names.addAll(Set.of("external_manage", "extension_manage", "dcs_manage")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return names;
    }

    @Test
    public void schemaListsMutateRoleRightsOperation() {
        String schema = new EdtValidateRequestTool().getParameterSchema();
        assertTrue(schema.contains("\"mutate_role_rights\"")); //$NON-NLS-1$
    }
}
