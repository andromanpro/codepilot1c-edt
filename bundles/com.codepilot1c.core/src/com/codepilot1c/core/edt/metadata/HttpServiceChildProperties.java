package com.codepilot1c.core.edt.metadata;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com._1c.g5.v8.dt.metadata.mdclass.HTTPMethod;

/**
 * Normalization and validation of the HTTP service child payload.
 *
 * <p>The EDT model is {@code HTTPService.getUrlTemplates(): EList<URLTemplate>}, where
 * {@code URLTemplate} carries a {@code template} string plus
 * {@code getMethods(): EList<Method>}, and {@code Method} carries an {@code HTTPMethod} verb and a
 * {@code handler} name. {@code HTTPMethod} is only the verb enumeration — the containment type is
 * {@code Method} — so the verb has to be resolved to a literal before the model layer ever sees
 * it.</p>
 *
 * <p>Both {@code MetadataRequestValidationService} (which mints the one-time token) and
 * {@code EdtMetadataService} (which performs the mutation) go through this class, so the payload
 * bound into a validation token is exactly the payload applied to the model.</p>
 */
public final class HttpServiceChildProperties {

    public static final String TEMPLATE = "template"; //$NON-NLS-1$
    public static final String HTTP_METHOD = "http_method"; //$NON-NLS-1$
    public static final String HANDLER = "handler"; //$NON-NLS-1$

    private HttpServiceChildProperties() {
    }

    /** Whether this kind is one of the HTTP service children handled here. */
    public static boolean applies(MetadataChildKind kind) {
        return kind == MetadataChildKind.HTTP_URL_TEMPLATE || kind == MetadataChildKind.HTTP_METHOD;
    }

    /**
     * Returns the properties map with the HTTP fields validated and canonicalized, or the input
     * unchanged for kinds this class does not handle.
     *
     * @throws MetadataOperationException when a required field is missing or malformed, so an
     *     incomplete URL template or method can never be half-created.
     */
    public static Map<String, Object> normalize(MetadataChildKind kind, Map<String, Object> properties) {
        if (!applies(kind)) {
            return properties;
        }
        rejectBatch(kind, properties);
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (properties != null) {
            normalized.putAll(properties);
        }
        if (kind == MetadataChildKind.HTTP_URL_TEMPLATE) {
            normalized.put(TEMPLATE, normalizeTemplate(normalized.get(TEMPLATE)));
        } else {
            normalized.put(HTTP_METHOD, normalizeVerb(normalized.get(HTTP_METHOD)).getLiteral());
            normalized.put(HANDLER, normalizeHandler(normalized.get(HANDLER)));
        }
        return normalized;
    }

    /** Resolves the canonical EDT verb for an already-normalized payload. */
    public static HTTPMethod resolveVerb(Map<String, Object> properties) {
        return normalizeVerb(properties == null ? null : properties.get(HTTP_METHOD));
    }

    public static String resolveTemplate(Map<String, Object> properties) {
        return normalizeTemplate(properties == null ? null : properties.get(TEMPLATE));
    }

    public static String resolveHandler(Map<String, Object> properties) {
        return normalizeHandler(properties == null ? null : properties.get(HANDLER));
    }

    /**
     * Refuses {@code properties.children} for HTTP service children.
     *
     * <p>A batch entry carries only {@code name}/{@code synonym}/{@code comment}, which cannot
     * express a URL template path, an HTTP verb or a handler. Accepting one would create
     * {@code URLTemplate} and {@code Method} objects with those required fields unset, so the batch
     * is refused outright rather than half-applied. Each HTTP child is created by its own
     * single-name request.</p>
     */
    public static void rejectBatch(MetadataChildKind kind, Map<String, Object> properties) {
        if (!applies(kind) || properties == null) {
            return;
        }
        if (properties.get("children") instanceof List<?> children && !children.isEmpty()) { //$NON-NLS-1$
            throw invalid("child_kind=" + kind.getDisplayName() //$NON-NLS-1$
                    + " does not support batch creation via properties.children: a URL template needs its own" //$NON-NLS-1$
                    + " template path and an HTTP method its own verb and handler, which a batch entry cannot" //$NON-NLS-1$
                    + " carry. Create each child with its own request."); //$NON-NLS-1$
        }
    }

    private static String normalizeTemplate(Object raw) {
        String value = raw == null ? null : String.valueOf(raw).trim();
        if (value == null || value.isEmpty()) {
            throw invalid("child_kind=URLTemplate requires the exact URL template path in properties.template," //$NON-NLS-1$
                    + " for example \"/state\" or \"/items/{id}\""); //$NON-NLS-1$
        }
        for (int index = 0; index < value.length(); index++) {
            char symbol = value.charAt(index);
            if (Character.isWhitespace(symbol) || symbol == '?' || symbol == '#') {
                throw invalid("properties.template must be a bare URL path without whitespace, query or fragment: " //$NON-NLS-1$
                        + value);
            }
        }
        return value;
    }

    private static HTTPMethod normalizeVerb(Object raw) {
        String value = raw == null ? null : String.valueOf(raw).trim();
        if (value == null || value.isEmpty()) {
            throw invalid("child_kind=HTTPMethod requires the HTTP verb in properties.http_method, one of " //$NON-NLS-1$
                    + literals());
        }
        String upper = value.toUpperCase(Locale.ROOT);
        for (HTTPMethod candidate : HTTPMethod.VALUES) {
            if (candidate.getLiteral().equalsIgnoreCase(upper) || candidate.getName().equalsIgnoreCase(upper)) {
                return candidate;
            }
        }
        throw invalid("Unsupported HTTP verb in properties.http_method: " + value + ". Supported verbs: " //$NON-NLS-1$ //$NON-NLS-2$
                + literals());
    }

    private static String normalizeHandler(Object raw) {
        String value = raw == null ? null : String.valueOf(raw).trim();
        if (value == null || value.isEmpty()) {
            throw invalid("child_kind=HTTPMethod requires properties.handler, the name of the procedure in the" //$NON-NLS-1$
                    + " HTTP service module that serves this method"); //$NON-NLS-1$
        }
        if (!MetadataNameValidator.isValidName(value)) {
            throw invalid("properties.handler must be a valid 1C identifier: " + value); //$NON-NLS-1$
        }
        return value;
    }

    private static String literals() {
        StringBuilder builder = new StringBuilder();
        for (HTTPMethod candidate : HTTPMethod.VALUES) {
            if (builder.length() > 0) {
                builder.append(", "); //$NON-NLS-1$
            }
            builder.append(candidate.getLiteral());
        }
        return builder.toString();
    }

    private static MetadataOperationException invalid(String message) {
        return new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_CHANGE, message, false);
    }
}
