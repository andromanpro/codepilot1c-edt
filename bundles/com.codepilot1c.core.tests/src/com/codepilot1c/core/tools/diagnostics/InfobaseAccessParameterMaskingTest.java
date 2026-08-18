package com.codepilot1c.core.tools.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class InfobaseAccessParameterMaskingTest {

    @Test
    public void masksSeparatedPassword() {
        assertEquals("/L ru /P <redacted>", InfobaseAccessParameterMasking.mask("/L ru /P s3cret")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void masksQuotedAndAttachedPasswordsCaseInsensitively() {
        assertEquals("/P <redacted>", InfobaseAccessParameterMasking.mask("/P\"s3 cret\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("/P <redacted>", InfobaseAccessParameterMasking.mask("/Ps3cret")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("/P <redacted>", InfobaseAccessParameterMasking.mask("/p s3cret")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void masksPasswordsWithSpacesAndEscapedQuotesWithoutRemainder() {
        assertEquals("/P <redacted>", InfobaseAccessParameterMasking.mask("/P my pass")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("/P <redacted>", InfobaseAccessParameterMasking.mask("/P \"pa\\\"ss\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("/P <redacted>", InfobaseAccessParameterMasking.mask("/P\"pa\"\"ss\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void masksCredentialBearingConnectionStringsCaseInsensitively() {
        assertEquals("/IBConnectionString\"<redacted>\"", InfobaseAccessParameterMasking.mask(
                "/IBConnectionString\"Srvr=srv;Ref=base;Usr=Admin;Pwd=s3cret;\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("/L ru /ibconnectionstring \"<redacted>\" /DisableStartupMessages", //$NON-NLS-1$
                InfobaseAccessParameterMasking.mask(
                        "/L ru /ibconnectionstring \"Srvr=srv;Password = other-secret;\" /DisableStartupMessages")); //$NON-NLS-1$
    }

    @Test
    public void preservesPrefetchAndAdjacentSwitches() {
        assertEquals("/Prefetch on", InfobaseAccessParameterMasking.mask("/Prefetch on")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("/P /L ru", InfobaseAccessParameterMasking.mask("/P /L ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("/P <redacted> /L ru", InfobaseAccessParameterMasking.mask("/P my pass /L ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("/P /Prefetch on", InfobaseAccessParameterMasking.mask("/P /Prefetch on")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("/Prefetch on /P <redacted> /L ru", //$NON-NLS-1$
                InfobaseAccessParameterMasking.mask("/Prefetch on /P secret /L ru")); //$NON-NLS-1$
    }

    @Test
    public void leavesOtherParametersAndBlankValuesUnchanged() {
        assertEquals("/N Админ", InfobaseAccessParameterMasking.mask("/N Админ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("/L ru", InfobaseAccessParameterMasking.mask("/L ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("/IBConnectionString\"Srvr=srv;Ref=base;\"", InfobaseAccessParameterMasking.mask(
                "/IBConnectionString\"Srvr=srv;Ref=base;\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("", InfobaseAccessParameterMasking.mask("")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(InfobaseAccessParameterMasking.mask(null));
    }

    @Test
    public void reportsWhetherMaskingChangedTheValue() {
        String original = "/L ru /P s3cret"; //$NON-NLS-1$
        String masked = InfobaseAccessParameterMasking.mask(original);

        assertTrue(InfobaseAccessParameterMasking.isMasked(original, masked));
        assertFalse(InfobaseAccessParameterMasking.isMasked("/L ru", "/L ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(InfobaseAccessParameterMasking.isMasked(null, null));
    }
}
