package com.codepilot1c.core.tools.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
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
 * Semantic creation of HTTP service children.
 *
 * <p>{@code create_metadata} could already create a top-level {@code HTTPService}, but its children
 * were rejected outright, so the only way to declare a URL template or an HTTP method was to edit
 * the {@code .mdo} by hand. The EDT model is
 * {@code HTTPService.getUrlTemplates(): EList<URLTemplate>}, {@code URLTemplate.getTemplate()} plus
 * {@code URLTemplate.getMethods(): EList<Method>}, and {@code Method} carries an
 * {@code HTTPMethod} enum verb and a {@code handler} name — note that the containment type is
 * {@code Method}, not {@code HTTPMethod}, which is only the verb enumeration.</p>
 */
public class AddMetadataChildHttpServiceTest {

    @Test
    public void schemaExposesUrlTemplateAndMethodKindsWithTheirExactProperties() {
        String schema = new AddMetadataChildTool().getParameterSchema();
        assertNotNull(schema);

        assertTrue("child_kind must offer URLTemplate:\n" + schema, schema.contains("\"URLTemplate\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("child_kind must offer HTTPMethod:\n" + schema, schema.contains("\"HTTPMethod\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must name the URL template path property:\n" + schema, //$NON-NLS-1$
                schema.contains("\"template\"")); //$NON-NLS-1$
        assertTrue("schema must name the HTTP verb property:\n" + schema, //$NON-NLS-1$
                schema.contains("\"http_method\"")); //$NON-NLS-1$
        assertTrue("schema must name the handler property:\n" + schema, //$NON-NLS-1$
                schema.contains("\"handler\"")); //$NON-NLS-1$
        assertTrue("schema must enumerate the EDT HTTP verbs:\n" + schema, //$NON-NLS-1$
                schema.contains("\"GET\"") && schema.contains("\"POST\"") && schema.contains("\"DELETE\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue("mutation stays token gated:\n" + schema, schema.contains("\"validation_token\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void urlTemplateAliasesResolveToTheUrlTemplateKind() {
        for (String alias : new String[] {"URLTemplate", "urltemplate", "url_template", "шаблонurl"}) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            assertEquals(alias, MetadataChildKind.HTTP_URL_TEMPLATE, MetadataChildKind.fromString(alias));
        }
        assertEquals("URLTemplate", MetadataChildKind.HTTP_URL_TEMPLATE.getDisplayName()); //$NON-NLS-1$
    }

    @Test
    public void httpMethodAliasesResolveToTheMethodKindWhoseDisplayNameMatchesTheEdtClass() {
        for (String alias : new String[] {"HTTPMethod", "httpmethod", "http_method", "метод"}) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            assertEquals(alias, MetadataChildKind.HTTP_METHOD, MetadataChildKind.fromString(alias));
        }
        // The EDT containment type is Method; HTTPMethod is only the verb enum, so the display name
        // that drives factory lookup and FQN building must be "Method".
        assertEquals("Method", MetadataChildKind.HTTP_METHOD.getDisplayName()); //$NON-NLS-1$
    }

    @Test
    public void unknownChildKindsStillFailClosed() {
        MetadataOperationException failure = assertThrows(MetadataOperationException.class,
                () -> MetadataChildKind.fromString("WebServiceOperation")); //$NON-NLS-1$
        assertEquals(MetadataOperationCode.INVALID_METADATA_KIND, failure.getCode());
    }

    @Test
    public void urlTemplateCreationNormalizesTheExactTemplatePathAndStaysTokenBound() {
        StubMetadataService metadataService = new StubMetadataService();
        StubValidationService validationService = new StubValidationService();
        AddMetadataChildTool tool = new AddMetadataChildTool(metadataService, validationService);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("project", "ДО.Агент"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("parent_fqn", "HTTPService.аг_api"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("child_kind", "URLTemplate"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "Корень"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("template", "/state"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("validation_token", "token-1"); //$NON-NLS-1$ //$NON-NLS-2$

        ToolResult result = tool.execute(params).join();

        assertTrue("URL template creation must succeed:\n" + result.getContent(), result.isSuccess()); //$NON-NLS-1$
        assertEquals(ValidationOperation.ADD_METADATA_CHILD, validationService.operation);
        assertEquals("ДО.Агент", validationService.projectName); //$NON-NLS-1$
        assertEquals("HTTP_URL_TEMPLATE", validationService.normalizedPayload.get("child_kind")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(MetadataChildKind.HTTP_URL_TEMPLATE, metadataService.lastRequest.childKind());
        assertEquals("/state", metadataService.lastRequest.properties().get("template")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void httpMethodCreationNormalizesVerbAndHandlerUnderTheUrlTemplateParent() {
        StubMetadataService metadataService = new StubMetadataService();
        StubValidationService validationService = new StubValidationService();
        AddMetadataChildTool tool = new AddMetadataChildTool(metadataService, validationService);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("project", "ДО.Агент"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("parent_fqn", "HTTPService.аг_api.URLTemplate.Корень"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("child_kind", "HTTPMethod"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "Получить"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("http_method", "get"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("handler", "СостояниеGET"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("validation_token", "token-1"); //$NON-NLS-1$ //$NON-NLS-2$

        ToolResult result = tool.execute(params).join();

        assertTrue("HTTP method creation must succeed:\n" + result.getContent(), result.isSuccess()); //$NON-NLS-1$
        assertEquals(MetadataChildKind.HTTP_METHOD, metadataService.lastRequest.childKind());
        assertEquals("HTTPService.аг_api.URLTemplate.Корень", metadataService.lastRequest.parentFqn()); //$NON-NLS-1$
        // The verb is normalized to the EDT HTTPMethod literal so the model layer never guesses.
        assertEquals("GET", metadataService.lastRequest.properties().get("http_method")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("СостояниеGET", metadataService.lastRequest.properties().get("handler")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void urlTemplateWithoutATemplatePathIsRejectedBeforeAnyMutation() {
        MetadataRequestValidationService validation = new MetadataRequestValidationService();

        MetadataOperationException failure = assertThrows(MetadataOperationException.class,
                () -> validation.normalizeAddChildPayload("P", "HTTPService.api", "URLTemplate", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        "Root", null, null, Map.of())); //$NON-NLS-1$

        assertEquals(MetadataOperationCode.INVALID_METADATA_CHANGE, failure.getCode());
        assertTrue(failure.getMessage(), failure.getMessage().contains("template")); //$NON-NLS-1$
    }

    @Test
    public void httpMethodWithoutVerbOrHandlerIsRejectedBeforeAnyMutation() {
        MetadataRequestValidationService validation = new MetadataRequestValidationService();

        MetadataOperationException noVerb = assertThrows(MetadataOperationException.class,
                () -> validation.normalizeAddChildPayload("P", "HTTPService.api.URLTemplate.Root", //$NON-NLS-1$ //$NON-NLS-2$
                        "HTTPMethod", "Get", null, null, Map.of("handler", "H"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals(MetadataOperationCode.INVALID_METADATA_CHANGE, noVerb.getCode());

        MetadataOperationException noHandler = assertThrows(MetadataOperationException.class,
                () -> validation.normalizeAddChildPayload("P", "HTTPService.api.URLTemplate.Root", //$NON-NLS-1$ //$NON-NLS-2$
                        "HTTPMethod", "Get", null, null, Map.of("http_method", "GET"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals(MetadataOperationCode.INVALID_METADATA_CHANGE, noHandler.getCode());
    }

    @Test
    public void anUnknownHttpVerbIsRejectedRatherThanPassedThroughToTheModel() {
        MetadataRequestValidationService validation = new MetadataRequestValidationService();

        MetadataOperationException failure = assertThrows(MetadataOperationException.class,
                () -> validation.normalizeAddChildPayload("P", "HTTPService.api.URLTemplate.Root", //$NON-NLS-1$ //$NON-NLS-2$
                        "HTTPMethod", "Fetch", null, null, //$NON-NLS-1$ //$NON-NLS-2$
                        Map.of("http_method", "FETCH", "handler", "H"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals(MetadataOperationCode.INVALID_METADATA_CHANGE, failure.getCode());
    }

    @Test
    public void aTemplatePathCarryingAQueryOrWhitespaceIsRejected() {
        MetadataRequestValidationService validation = new MetadataRequestValidationService();

        for (String invalid : new String[] {"/items?page=1", "/it ems", "/frag#ment", " "}) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            MetadataOperationException failure = assertThrows(invalid, MetadataOperationException.class,
                    () -> validation.normalizeAddChildPayload("P", "HTTPService.api", "URLTemplate", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            "Root", null, null, Map.of("template", invalid))); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(MetadataOperationCode.INVALID_METADATA_CHANGE, failure.getCode());
        }
    }

    @Test
    public void normalizedHttpPayloadIsPartOfTheTokenBoundPayloadSoItCannotBeSwappedAfterValidation() {
        MetadataRequestValidationService validation = new MetadataRequestValidationService();

        Map<String, Object> payload = validation.normalizeAddChildPayload("P", //$NON-NLS-1$
                "HTTPService.api.URLTemplate.Root", "HTTPMethod", "Get", null, null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                Map.of("http_method", "post", "handler", "RootPOST")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals("HTTP_METHOD", payload.get("child_kind")); //$NON-NLS-1$ //$NON-NLS-2$
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) payload.get("properties"); //$NON-NLS-1$
        assertEquals("POST", properties.get("http_method")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("RootPOST", properties.get("handler")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void batchChildrenAreRefusedForHttpKindsBecauseTheirRequiredFieldsArePerChild() {
        // A batch entry carries only name/synonym/comment, so URL templates and methods would be
        // created with no path, verb or handler. Fail closed rather than half-create them.
        MetadataRequestValidationService validation = new MetadataRequestValidationService();

        MetadataOperationException templateBatch = assertThrows(MetadataOperationException.class,
                () -> validation.normalizeAddChildPayload("P", "HTTPService.api", "URLTemplate", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        null, null, null,
                        Map.of("template", "/state", "children", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                java.util.List.of(Map.of("name", "A"), Map.of("name", "B"))))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(MetadataOperationCode.INVALID_METADATA_CHANGE, templateBatch.getCode());
        assertTrue(templateBatch.getMessage(), templateBatch.getMessage().contains("children")); //$NON-NLS-1$

        MetadataOperationException methodBatch = assertThrows(MetadataOperationException.class,
                () -> validation.normalizeAddChildPayload("P", "HTTPService.api.URLTemplate.Root", //$NON-NLS-1$ //$NON-NLS-2$
                        "HTTPMethod", null, null, null, //$NON-NLS-1$
                        Map.of("http_method", "GET", "handler", "H", "children", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                                java.util.List.of(Map.of("name", "A"))))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(MetadataOperationCode.INVALID_METADATA_CHANGE, methodBatch.getCode());
    }

    @Test
    public void httpChildrenAlwaysRequireAnExplicitSingleName() {
        MetadataRequestValidationService validation = new MetadataRequestValidationService();

        MetadataOperationException failure = assertThrows(MetadataOperationException.class,
                () -> validation.normalizeAddChildPayload("P", "HTTPService.api", "URLTemplate", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        null, null, null, Map.of("template", "/state"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(MetadataOperationCode.INVALID_METADATA_NAME, failure.getCode());
    }

    private static final class StubMetadataService extends EdtMetadataService {
        private AddMetadataChildRequest lastRequest;

        @Override
        public MetadataOperationResult addMetadataChild(AddMetadataChildRequest request) {
            lastRequest = request;
            String fqn = request.parentFqn() + "." + request.childKind().getDisplayName() + "." + request.name(); //$NON-NLS-1$ //$NON-NLS-2$
            return new MetadataOperationResult(true, request.projectName(), request.childKind().name(),
                    request.name(), fqn, "Metadata child object created successfully"); //$NON-NLS-1$
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
                        MetadataOperationCode.KNOWLEDGE_REQUIRED, "unexpected token", false); //$NON-NLS-1$
            }
            this.operation = operation;
            this.projectName = projectName;
            return normalizedPayload;
        }

        @Override
        public Map<String, Object> normalizeAddChildPayload(String project, String parentFqn,
                String childKindValue, String name, String synonym, String comment,
                Map<String, Object> properties) {
            normalizedPayload = super.normalizeAddChildPayload(
                    project, parentFqn, childKindValue, name, synonym, comment, properties);
            return normalizedPayload;
        }
    }
}
