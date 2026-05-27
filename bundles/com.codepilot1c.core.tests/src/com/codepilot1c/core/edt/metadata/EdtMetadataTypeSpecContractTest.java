package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Method;
import java.util.Map;

import org.junit.Test;

public class EdtMetadataTypeSpecContractTest {

    @Test
    public void fractionDigitsIsAcceptedAsNumberScaleAlias() throws Exception {
        Object typeSpec = normalizeTypeSpec(Map.of(
                "type", "Number", //$NON-NLS-1$ //$NON-NLS-2$
                "numberQualifiers", Map.of( //$NON-NLS-1$
                        "precision", 15, //$NON-NLS-1$
                        "fractionDigits", 3))); //$NON-NLS-1$

        assertEquals(15, intAccessor(typeSpec, "numberPrecision")); //$NON-NLS-1$
        assertEquals(3, intAccessor(typeSpec, "numberScale")); //$NON-NLS-1$
    }

    @Test
    public void rootFractionDigitsIsAcceptedAsNumberScaleAlias() throws Exception {
        Object typeSpec = normalizeTypeSpec(Map.of(
                "type", "Number", //$NON-NLS-1$ //$NON-NLS-2$
                "precision", 15, //$NON-NLS-1$
                "fractionDigits", 3)); //$NON-NLS-1$

        assertEquals(15, intAccessor(typeSpec, "numberPrecision")); //$NON-NLS-1$
        assertEquals(3, intAccessor(typeSpec, "numberScale")); //$NON-NLS-1$
    }

    private Object normalizeTypeSpec(Object value) throws Exception {
        Method method = EdtMetadataService.class.getDeclaredMethod("normalizeTypeSpec", Object.class); //$NON-NLS-1$
        method.setAccessible(true);
        return method.invoke(new EdtMetadataService(), value);
    }

    private int intAccessor(Object instance, String methodName) throws Exception {
        Method method = instance.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return ((Integer) method.invoke(instance)).intValue();
    }
}
