package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link BmSynonymLocaleResolver}.
 *
 * <p>The resolver is the pure-data backbone of the BF-8908 Gap 4 fix for
 * locale handling: previously every newly-created synonym landed under
 * key {@code "ru"} regardless of the project's actual default language.
 * This test pins each step of the resolution chain so a refactor cannot
 * silently revert to the historical hard-coded behaviour.</p>
 */
public class BmSynonymLocaleResolverTest {

    @Test
    public void preferenceOrder_explicitDefaultWins() {
        assertEquals("en", BmSynonymLocaleResolver.resolve("en", List.of("ru", "en"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals("de", BmSynonymLocaleResolver.resolve("de", List.of("en", "ru"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void preferenceOrder_firstNonBlankFromConfigurationWhenDefaultMissing() {
        assertEquals("en", BmSynonymLocaleResolver.resolve(null, List.of("en", "ru"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("ru", BmSynonymLocaleResolver.resolve("", List.of("ru"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("uk", BmSynonymLocaleResolver.resolve("   ", List.of("uk", "en"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void preferenceOrder_skipsBlankConfigurationEntries() {
        assertEquals("en", BmSynonymLocaleResolver.resolve(null, Arrays.asList(null, "", "  ", "en", "ru"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    @Test
    public void preferenceOrder_fallbackWhenNothingUsable() {
        assertEquals(BmSynonymLocaleResolver.FALLBACK_LANGUAGE_CODE,
                BmSynonymLocaleResolver.resolve(null, List.of()));
        assertEquals(BmSynonymLocaleResolver.FALLBACK_LANGUAGE_CODE,
                BmSynonymLocaleResolver.resolve(null, null));
        assertEquals(BmSynonymLocaleResolver.FALLBACK_LANGUAGE_CODE,
                BmSynonymLocaleResolver.resolve("", Arrays.asList(null, "  "))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void fallbackKeyMatchesHistoricalBehaviour() {
        // Existing AM and other Russian-only fixtures must keep landing on "ru" so
        // already-checked-in .mdo files stay valid after the resolver lands.
        assertEquals("ru", BmSynonymLocaleResolver.FALLBACK_LANGUAGE_CODE); //$NON-NLS-1$
    }

    @Test
    public void resolveTrimsWhitespaceFromAcceptedCode() {
        // Configuration files occasionally carry trailing whitespace in the
        // languageCode; we must not write "ru " or " en" into the synonym map.
        assertEquals("en", BmSynonymLocaleResolver.resolve(" en ", List.of("ru"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("uk", BmSynonymLocaleResolver.resolve(null, List.of(" uk ", "en"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
