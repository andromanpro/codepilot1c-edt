package com.codepilot1c.core.tools.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.metadata.AddMetadataChildRequest;
import com.codepilot1c.core.edt.metadata.EdtMetadataService;
import com.codepilot1c.core.edt.metadata.MetadataChildKind;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.metadata.MetadataOperationResult;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.tools.ToolResult;

/**
 * Regression tests for {@code add_metadata_child} EnumValue support.
 *
 * <p>Background: although {@link MetadataChildKind#ENUM_VALUE} has long been
 * defined and {@link EdtMetadataService#addMetadataChild} dispatches it via
 * {@code MdClassFactory.createEnumEnumValue}, the JSON schema enum advertised
 * by {@link AddMetadataChildTool} omitted {@code "EnumValue"}.  Strict-schema
 * MCP clients then rejected {@code child_kind: "EnumValue"} client-side, with
 * no API path to populate an Enum's values without writing the {@code .mdo}
 * by hand.  This test pins both the schema visibility and the dispatch
 * round-trip so the gap stays closed.</p>
 */
public class AddMetadataChildToolEnumValueTest {

    @Test
    public void schemaExposesEnumValueInChildKind() {
        AddMetadataChildTool tool = new AddMetadataChildTool();
        String schema = tool.getParameterSchema();
        assertNotNull(schema);
        assertTrue("schema must list EnumValue in child_kind enum:\n" + schema, //$NON-NLS-1$
                schema.contains("\"EnumValue\"")); //$NON-NLS-1$
    }

    @Test
    public void enumValueIsDispatchedToMetadataServiceAsEnumValueKind() {
        StubMetadataService metadataService = new StubMetadataService();
        StubValidationService validationService = new StubValidationService();
        AddMetadataChildTool tool = new AddMetadataChildTool(metadataService, validationService);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("project", "AM"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("parent_fqn", "Enum.BankCommissionMovementTypes"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("child_kind", "EnumValue"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "Outgoing"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("synonym", "Outgoing"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("validation_token", "token-1"); //$NON-NLS-1$ //$NON-NLS-2$

        ToolResult result = tool.execute(params).join();

        assertTrue("tool execute must succeed for EnumValue:\n" + result.getContent(), //$NON-NLS-1$
                result.isSuccess());
        assertEquals(ValidationOperation.ADD_METADATA_CHILD, validationService.operation);
        assertEquals("AM", validationService.projectName); //$NON-NLS-1$
        assertEquals("ENUM_VALUE", validationService.normalizedPayload.get("child_kind")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("metadata service must receive the request", metadataService.lastRequest); //$NON-NLS-1$
        assertEquals(MetadataChildKind.ENUM_VALUE, metadataService.lastRequest.childKind());
        assertEquals("Enum.BankCommissionMovementTypes", metadataService.lastRequest.parentFqn()); //$NON-NLS-1$
        assertEquals("Outgoing", metadataService.lastRequest.name()); //$NON-NLS-1$
    }

    @Test
    public void caseInsensitiveAliasesAreAcceptedForEnumValue() {
        // The MetadataChildKind.fromString() helper accepts "enum_value", "enumvalue" and
        // "значениеперечисления" — all should reach the service as ENUM_VALUE.  We test
        // the canonical "enumvalue" alias to confirm the lower-cased path round-trips
        // without losing the kind.
        StubMetadataService metadataService = new StubMetadataService();
        StubValidationService validationService = new StubValidationService();
        AddMetadataChildTool tool = new AddMetadataChildTool(metadataService, validationService);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("project", "AM"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("parent_fqn", "Enum.X"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("child_kind", "enumvalue"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "Y"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("validation_token", "token-1"); //$NON-NLS-1$ //$NON-NLS-2$

        ToolResult result = tool.execute(params).join();

        assertTrue(result.isSuccess());
        assertEquals(MetadataChildKind.ENUM_VALUE, metadataService.lastRequest.childKind());
    }

    private static final class StubMetadataService extends EdtMetadataService {
        private AddMetadataChildRequest lastRequest;

        @Override
        public MetadataOperationResult addMetadataChild(AddMetadataChildRequest request) {
            lastRequest = request;
            String fqn = request.parentFqn() + "." + request.childKind().getDisplayName() + "." + request.name(); //$NON-NLS-1$ //$NON-NLS-2$
            return new MetadataOperationResult(
                    true,
                    request.projectName(),
                    request.childKind().name(),
                    request.name(),
                    fqn,
                    "Metadata child object created successfully"); //$NON-NLS-1$
        }
    }

    private static final class StubValidationService extends MetadataRequestValidationService {
        private ValidationOperation operation;
        private String projectName;
        private Map<String, Object> normalizedPayload;

        @Override
        public Map<String, Object> consumeToken(String token, ValidationOperation operation, String projectName) {
            if (!"token-1".equals(token)) { //$NON-NLS-1$
                throw new MetadataOperationException(
                        MetadataOperationCode.KNOWLEDGE_REQUIRED,
                        "unexpected token", false); //$NON-NLS-1$
            }
            this.operation = operation;
            this.projectName = projectName;
            // Return whatever normalizeAddChildPayload produced via super.
            return normalizedPayload;
        }

        @Override
        public Map<String, Object> normalizeAddChildPayload(
                String project,
                String parentFqn,
                String childKindValue,
                String name,
                String synonym,
                String comment,
                Map<String, Object> properties) {
            Map<String, Object> payload = super.normalizeAddChildPayload(
                    project, parentFqn, childKindValue, name, synonym, comment, properties);
            normalizedPayload = payload;
            return payload;
        }
    }
}
