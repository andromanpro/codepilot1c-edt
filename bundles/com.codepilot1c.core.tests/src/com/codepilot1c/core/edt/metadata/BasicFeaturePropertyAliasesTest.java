package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Optional;

import org.junit.Test;

/**
 * Tests for {@link BasicFeaturePropertyAliases}.
 *
 * <p>Pins the alias coverage for the four enum-typed properties an agent
 * commonly supplies when creating a BasicFeature via add_metadata_child.
 * Before the BF-8908 Gap 1 fix these were silently dropped: the tool
 * accepted them, validation echoed them, but
 * {@code applyBasicFeatureCreateProperties} only handled {@code multiLine}
 * — every fillChecking / dataHistory / fullTextSearch / indexing payload
 * landed in the .mdo as the platform default ("DontCheck"/"DontUse"/
 * "DontUse"/"DontIndex").</p>
 */
public class BasicFeaturePropertyAliasesTest {

    // --- fillChecking --------------------------------------------------------

    @Test
    public void fillChecking_canonicalUpperSnakeRoundTrips() {
        assertEqualsOptional("DONT_CHECK", BasicFeaturePropertyAliases.resolveFillChecking("DONT_CHECK")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEqualsOptional("SHOW_ERROR", BasicFeaturePropertyAliases.resolveFillChecking("SHOW_ERROR")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void fillChecking_camelAndSpacedAliasesResolveToCanonical() {
        // The agent payload from BF-8908 used "ShowError" (camel-case, no separator).
        assertEqualsOptional("SHOW_ERROR", BasicFeaturePropertyAliases.resolveFillChecking("ShowError")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEqualsOptional("SHOW_ERROR", BasicFeaturePropertyAliases.resolveFillChecking("show_error")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEqualsOptional("SHOW_ERROR", BasicFeaturePropertyAliases.resolveFillChecking("show error")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEqualsOptional("SHOW_ERROR", BasicFeaturePropertyAliases.resolveFillChecking("Show-Error")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEqualsOptional("DONT_CHECK", BasicFeaturePropertyAliases.resolveFillChecking("DontCheck")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEqualsOptional("DONT_CHECK", BasicFeaturePropertyAliases.resolveFillChecking("Don't Check")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void fillChecking_booleanShapesResolve() {
        assertEqualsOptional("SHOW_ERROR", BasicFeaturePropertyAliases.resolveFillChecking("true")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEqualsOptional("SHOW_ERROR", BasicFeaturePropertyAliases.resolveFillChecking("yes")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEqualsOptional("SHOW_ERROR", BasicFeaturePropertyAliases.resolveFillChecking("required")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEqualsOptional("DONT_CHECK", BasicFeaturePropertyAliases.resolveFillChecking("false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEqualsOptional("DONT_CHECK", BasicFeaturePropertyAliases.resolveFillChecking("no")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEqualsOptional("DONT_CHECK", BasicFeaturePropertyAliases.resolveFillChecking("none")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void fillChecking_unknownAndBlankReturnEmpty() {
        assertTrue(BasicFeaturePropertyAliases.resolveFillChecking("MAYBE").isEmpty()); //$NON-NLS-1$
        assertTrue(BasicFeaturePropertyAliases.resolveFillChecking("").isEmpty()); //$NON-NLS-1$
        assertTrue(BasicFeaturePropertyAliases.resolveFillChecking(null).isEmpty());
    }

    // --- dataHistory ---------------------------------------------------------

    @Test
    public void dataHistory_resolvesCommonAgentInputs() {
        // BF-8908 used "Use" — canonical short form.
        assertEqualsOptional("USE", BasicFeaturePropertyAliases.resolveDataHistory("Use")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEqualsOptional("USE", BasicFeaturePropertyAliases.resolveDataHistory("USE")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEqualsOptional("USE", BasicFeaturePropertyAliases.resolveDataHistory("on")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEqualsOptional("DONT_USE", BasicFeaturePropertyAliases.resolveDataHistory("DontUse")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEqualsOptional("DONT_USE", BasicFeaturePropertyAliases.resolveDataHistory("dont_use")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEqualsOptional("DONT_USE", BasicFeaturePropertyAliases.resolveDataHistory("Don't Use")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEqualsOptional("DONT_USE", BasicFeaturePropertyAliases.resolveDataHistory("off")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void dataHistory_unknownReturnsEmpty() {
        assertTrue(BasicFeaturePropertyAliases.resolveDataHistory("Sometimes").isEmpty()); //$NON-NLS-1$
        assertTrue(BasicFeaturePropertyAliases.resolveDataHistory(null).isEmpty());
    }

    // --- fullTextSearch ------------------------------------------------------

    @Test
    public void fullTextSearch_resolvesCommonInputs() {
        assertEqualsOptional("USE", BasicFeaturePropertyAliases.resolveFullTextSearch("Use")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEqualsOptional("USE", BasicFeaturePropertyAliases.resolveFullTextSearch("yes")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEqualsOptional("USE", BasicFeaturePropertyAliases.resolveFullTextSearch("on")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEqualsOptional("DONT_USE", BasicFeaturePropertyAliases.resolveFullTextSearch("DontUse")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEqualsOptional("DONT_USE", BasicFeaturePropertyAliases.resolveFullTextSearch("none")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- indexing ------------------------------------------------------------

    @Test
    public void indexing_resolvesAllThreeStates() {
        assertEqualsOptional("INDEX", BasicFeaturePropertyAliases.resolveIndexing("Index")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEqualsOptional("INDEX", BasicFeaturePropertyAliases.resolveIndexing("INDEX")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEqualsOptional("INDEX", BasicFeaturePropertyAliases.resolveIndexing("yes")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEqualsOptional("DONT_INDEX", BasicFeaturePropertyAliases.resolveIndexing("DontIndex")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEqualsOptional("DONT_INDEX", BasicFeaturePropertyAliases.resolveIndexing("dont_index")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEqualsOptional("DONT_INDEX", BasicFeaturePropertyAliases.resolveIndexing("none")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEqualsOptional("INDEX_WITH_ADDITIONAL_ORDER", //$NON-NLS-1$
                BasicFeaturePropertyAliases.resolveIndexing("IndexWithAdditionalOrder")); //$NON-NLS-1$
        assertEqualsOptional("INDEX_WITH_ADDITIONAL_ORDER", //$NON-NLS-1$
                BasicFeaturePropertyAliases.resolveIndexing("INDEX_WITH_ADDITIONAL_ORDER")); //$NON-NLS-1$
        assertEqualsOptional("INDEX_WITH_ADDITIONAL_ORDER", //$NON-NLS-1$
                BasicFeaturePropertyAliases.resolveIndexing("WithAdditionalOrder")); //$NON-NLS-1$
    }

    @Test
    public void indexing_unknownReturnsEmpty() {
        assertTrue(BasicFeaturePropertyAliases.resolveIndexing("Maybe").isEmpty()); //$NON-NLS-1$
    }

    // --- behaviour cross-cutting --------------------------------------------

    @Test
    public void resolversAreCaseInsensitive() {
        // Already tested per-helper but pin the contract one more time:
        assertEqualsOptional("SHOW_ERROR", //$NON-NLS-1$
                BasicFeaturePropertyAliases.resolveFillChecking("show_error")); //$NON-NLS-1$
        assertEqualsOptional("SHOW_ERROR", //$NON-NLS-1$
                BasicFeaturePropertyAliases.resolveFillChecking("SHOW_ERROR")); //$NON-NLS-1$
        assertEqualsOptional("SHOW_ERROR", //$NON-NLS-1$
                BasicFeaturePropertyAliases.resolveFillChecking("ShowError")); //$NON-NLS-1$
    }

    @Test
    public void resolversTrimWhitespace() {
        assertEqualsOptional("USE", BasicFeaturePropertyAliases.resolveDataHistory("  Use  ")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void resolversDoNotCrossContaminate() {
        // "Index" must NOT resolve under fillChecking, dataHistory, or fullTextSearch.
        assertFalse(BasicFeaturePropertyAliases.resolveFillChecking("Index").isPresent()); //$NON-NLS-1$
        assertFalse(BasicFeaturePropertyAliases.resolveDataHistory("Index").isPresent()); //$NON-NLS-1$
        assertFalse(BasicFeaturePropertyAliases.resolveFullTextSearch("Index").isPresent()); //$NON-NLS-1$
        // "ShowError" must NOT resolve under dataHistory/fullTextSearch/indexing.
        assertFalse(BasicFeaturePropertyAliases.resolveDataHistory("ShowError").isPresent()); //$NON-NLS-1$
        assertFalse(BasicFeaturePropertyAliases.resolveFullTextSearch("ShowError").isPresent()); //$NON-NLS-1$
        assertFalse(BasicFeaturePropertyAliases.resolveIndexing("ShowError").isPresent()); //$NON-NLS-1$
    }

    private static void assertEqualsOptional(String expected, Optional<String> actual) {
        assertTrue("expected Optional with '" + expected + "' but got empty", actual.isPresent()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(expected, actual.get());
    }
}
