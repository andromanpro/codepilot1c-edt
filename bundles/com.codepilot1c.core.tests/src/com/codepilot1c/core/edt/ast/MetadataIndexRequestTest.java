package com.codepilot1c.core.edt.ast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class MetadataIndexRequestTest {

    @Test
    public void omittedPaginationUsesBackwardsCompatibleDefaults() {
        MetadataIndexRequest request = MetadataIndexRequest.fromParameters(
                Map.of("projectName", "Demo")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(200, request.limit());
        assertEquals(0, request.offset());

        MetadataIndexRequest legacy = new MetadataIndexRequest(
                "Demo", "all", null, 10, "ru"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(0, legacy.offset());
    }

    @Test
    public void parsesIntegralOffsetAndAcceptsIntegerMaximum() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("projectName", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        parameters.put("offset", Double.valueOf(17)); //$NON-NLS-1$
        assertEquals(17, MetadataIndexRequest.fromParameters(parameters).offset());

        parameters.put("offset", String.valueOf(Integer.MAX_VALUE)); //$NON-NLS-1$
        assertEquals(Integer.MAX_VALUE, MetadataIndexRequest.fromParameters(parameters).offset());
    }

    @Test
    public void rejectsNegativeFractionalMalformedAndOverflowOffsets() {
        assertInvalidOffset(Integer.valueOf(-1));
        assertInvalidOffset(Double.valueOf(1.5));
        assertInvalidOffset("not-a-number"); //$NON-NLS-1$
        assertInvalidOffset("2147483648"); //$NON-NLS-1$
    }

    @Test
    public void rejectsFractionalMalformedAndOverflowLimits() {
        assertInvalidParameter("limit", Double.valueOf(1.5)); //$NON-NLS-1$
        assertInvalidParameter("limit", "not-a-number"); //$NON-NLS-1$ //$NON-NLS-2$
        assertInvalidParameter("limit", "2147483648"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void validatesLimitAndDirectOffsetBoundaries() {
        new MetadataIndexRequest("Demo", null, null, 1, null, 0).validate(); //$NON-NLS-1$
        new MetadataIndexRequest("Demo", null, null, 1000, null, Integer.MAX_VALUE).validate(); //$NON-NLS-1$

        assertInvalid(new MetadataIndexRequest("Demo", null, null, 0, null, 0)); //$NON-NLS-1$
        assertInvalid(new MetadataIndexRequest("Demo", null, null, 1001, null, 0)); //$NON-NLS-1$
        assertInvalid(new MetadataIndexRequest("Demo", null, null, 1, null, -1)); //$NON-NLS-1$
    }

    private static void assertInvalidOffset(Object value) {
        assertInvalidParameter("offset", value); //$NON-NLS-1$
    }

    private static void assertInvalidParameter(String name, Object value) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("projectName", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        parameters.put(name, value);
        try {
            MetadataIndexRequest.fromParameters(parameters);
            fail("Expected invalid " + name + ": " + value); //$NON-NLS-1$ //$NON-NLS-2$
        } catch (EdtAstException e) {
            assertEquals(EdtAstErrorCode.INVALID_ARGUMENT, e.getCode());
        }
    }

    private static void assertInvalid(MetadataIndexRequest request) {
        try {
            request.validate();
            fail("Expected invalid request: " + request); //$NON-NLS-1$
        } catch (EdtAstException e) {
            assertEquals(EdtAstErrorCode.INVALID_ARGUMENT, e.getCode());
        }
    }
}
