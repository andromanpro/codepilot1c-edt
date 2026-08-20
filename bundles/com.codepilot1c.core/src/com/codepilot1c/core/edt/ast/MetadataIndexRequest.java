package com.codepilot1c.core.edt.ast;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/**
 * Request for metadata index scan.
 */
public record MetadataIndexRequest(
        String projectName,
        String scope,
        String nameContains,
        int limit,
        String language,
        int offset
) {
    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 1000;

    /**
     * Backwards-compatible constructor for callers created before offset pagination.
     */
    public MetadataIndexRequest(
            String projectName,
            String scope,
            String nameContains,
            int limit,
            String language) {
        this(projectName, scope, nameContains, limit, language, 0);
    }

    public static MetadataIndexRequest fromParameters(Map<String, Object> parameters) {
        String projectName = asString(parameters.get("projectName")); //$NON-NLS-1$
        String scope = asString(parameters.get("scope")); //$NON-NLS-1$
        String nameContains = asString(parameters.get("nameContains")); //$NON-NLS-1$
        int limit = asInteger(parameters.get("limit"), DEFAULT_LIMIT, "limit"); //$NON-NLS-1$ //$NON-NLS-2$
        String language = asString(parameters.get("language")); //$NON-NLS-1$
        int offset = asInteger(parameters.get("offset"), 0, "offset"); //$NON-NLS-1$ //$NON-NLS-2$
        MetadataIndexRequest request = new MetadataIndexRequest(
                projectName, scope, nameContains, limit, language, offset);
        request.validate();
        return request;
    }

    public void validate() {
        if (projectName == null || projectName.isBlank()) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "projectName is required", false); //$NON-NLS-1$
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "limit must be between 1 and " + MAX_LIMIT, false); //$NON-NLS-1$
        }
        if (offset < 0) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "offset must be greater than or equal to 0", false); //$NON-NLS-1$
        }
    }

    public String normalizedScope() {
        if (scope == null || scope.isBlank()) {
            return "all"; //$NON-NLS-1$
        }
        return scope.trim().toLowerCase(Locale.ROOT);
    }

    public String normalizedNameContains() {
        if (nameContains == null || nameContains.isBlank()) {
            return ""; //$NON-NLS-1$
        }
        return nameContains.trim().toLowerCase(Locale.ROOT);
    }

    public String normalizedLanguage() {
        if (language == null || language.isBlank()) {
            return null;
        }
        return language.trim().toLowerCase(Locale.ROOT);
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static int asInteger(Object value, int defaultValue, String parameterName) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim()).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    parameterName + " must be an integer", false); //$NON-NLS-1$
        }
    }
}
