package com.codepilot1c.core.edt.validation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * The {@code edt_validate_request} normalization path for HTTP service children.
 *
 * <p>{@code add_metadata_child} advertises {@code template}, {@code http_method} and
 * {@code handler} as top-level parameters, and merges them into {@code properties} before calling
 * the metadata service. The validate step that mints the one-time token has to perform the same
 * merge: otherwise a request written exactly as the published schema documents it either fails to
 * mint a token at all, or mints one bound to a payload that differs from the one the mutation
 * applies.</p>
 *
 * <p>{@code validateAndIssueToken} itself needs a live EDT project for its readiness gate, so these
 * tests drive the payload normalization it delegates to.</p>
 */
public class MetadataRequestValidationServiceHttpChildTest {

    @Test
    public void topLevelUrlTemplatePathReachesTheTokenBoundPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project", "P"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.put("parent_fqn", "HTTPService.api"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.put("child_kind", "URLTemplate"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.put("name", "Root"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.put("template", "/state"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("/state", properties(normalize(payload)).get("template")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void topLevelVerbAndHandlerReachTheTokenBoundPayloadAlreadyCanonicalized() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project", "P"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.put("parent_fqn", "HTTPService.api.URLTemplate.Root"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.put("child_kind", "HTTPMethod"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.put("name", "Get"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.put("http_method", "get"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.put("handler", "RootGET"); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, Object> properties = properties(normalize(payload));
        assertEquals("GET", properties.get("http_method")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("RootGET", properties.get("handler")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void explicitPropertiesWinOverTopLevelSoOneUnambiguousPayloadIsBound() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project", "P"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.put("parent_fqn", "HTTPService.api"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.put("child_kind", "URLTemplate"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.put("name", "Root"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.put("template", "/from-top-level"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.put("properties", Map.of("template", "/from-properties")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("/from-properties", properties(normalize(payload)).get("template")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aMissingTopLevelTemplateStillFailsClosedAtValidateTime() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project", "P"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.put("parent_fqn", "HTTPService.api"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.put("child_kind", "URLTemplate"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.put("name", "Root"); //$NON-NLS-1$ //$NON-NLS-2$

        MetadataOperationException failure =
                assertThrows(MetadataOperationException.class, () -> normalize(payload));
        assertEquals(MetadataOperationCode.INVALID_METADATA_CHANGE, failure.getCode());
        assertTrue(failure.getMessage(), failure.getMessage().contains("template")); //$NON-NLS-1$
    }

    @Test
    public void topLevelTemplateTypeStillReachesNonHttpChildren() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project", "P"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.put("parent_fqn", "Catalog.Items"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.put("child_kind", "Template"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.put("name", "Print"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.put("template_type", "html"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("html", properties(normalize(payload)).get("template_type")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Map<String, Object> normalize(Map<String, Object> payload) {
        return new MetadataRequestValidationService().normalizePayload(
                new ValidationRequest("P", ValidationOperation.ADD_METADATA_CHILD, payload), //$NON-NLS-1$
                new ArrayList<>());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> normalized) {
        Object value = normalized.get("properties"); //$NON-NLS-1$
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }
}
