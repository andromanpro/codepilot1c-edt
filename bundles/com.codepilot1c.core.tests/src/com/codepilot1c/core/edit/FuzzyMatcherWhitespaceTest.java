/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link FuzzyMatcher#findMatch} with the
 * {@link MatchStrategy#NORMALIZE_WHITESPACE} fallback.
 *
 * <p>Pinned regression: a search text anchored at EOF in a CRLF document used
 * to produce an off-by-one start (the {@code \n} half of a {@code \r\n} pair
 * was double-counted) and a too-short end (only the orphaned {@code \n} was
 * captured). Applying the resulting match left the original closing tag in
 * place while the replacement added a new one, producing the famous
 * {@code </root></root>} duplication seen on {@code .mdo} edits.
 */
public class FuzzyMatcherWhitespaceTest {

    @Test
    public void crlfDocumentEofAnchoredSearchCapturesFullClosingTag() {
        String doc = "<root>\r\n  <a/>\r\n</root>"; //$NON-NLS-1$
        String search = "</root>"; //$NON-NLS-1$

        MatchResult result = new FuzzyMatcher().findMatch(search, doc);

        assertTrue("match must succeed via whitespace normalization", result.isSuccess()); //$NON-NLS-1$
        MatchLocation location = result.getLocation().orElseThrow();
        assertNotNull(location);
        assertEquals("matched text should be exactly the closing tag", //$NON-NLS-1$
                "</root>", location.getMatchedText()); //$NON-NLS-1$
        assertEquals("end offset must reach EOF", doc.length(), location.getEndOffset()); //$NON-NLS-1$
        assertEquals("start offset must point at '<' of '</root>'", //$NON-NLS-1$
                doc.lastIndexOf("</root>"), location.getStartOffset()); //$NON-NLS-1$
    }

    @Test
    public void crlfDocumentMultiLineSearchTouchingEofMatchesCleanly() {
        String doc = "<root>\r\n  <a/>\r\n</root>"; //$NON-NLS-1$
        // Search text uses LF (typical when LLM-generated) and ends at the closing tag with no trailing newline.
        String search = "  <a/>\n</root>"; //$NON-NLS-1$

        MatchResult result = new FuzzyMatcher().findMatch(search, doc);

        assertTrue(result.isSuccess());
        MatchLocation location = result.getLocation().orElseThrow();
        assertEquals("matched text should cover both lines, preserving CRLF", //$NON-NLS-1$
                "  <a/>\r\n</root>", location.getMatchedText()); //$NON-NLS-1$
        assertEquals(doc.length(), location.getEndOffset());
    }

    @Test
    public void lfDocumentTrailingWhitespaceTolerated() {
        // Document line has trailing spaces; search does not.
        String doc = "alpha\nbeta   \ngamma"; //$NON-NLS-1$
        String search = "beta\ngamma"; //$NON-NLS-1$

        MatchResult result = new FuzzyMatcher().findMatch(search, doc);

        assertTrue(result.isSuccess());
        MatchLocation location = result.getLocation().orElseThrow();
        assertEquals("beta   \ngamma", location.getMatchedText()); //$NON-NLS-1$
        assertEquals(doc.length(), location.getEndOffset());
    }

    @Test
    public void replacementAtEofDoesNotDuplicateClosingTag() {
        // Simulates the .mdo edit reported in the 2026-05-17 retest:
        // - document ends with </close> and no trailing newline
        // - search text covers the tail through </close>
        // - new content keeps </close> at the end
        String doc = "<root>\r\n  <a/>\r\n</close>"; //$NON-NLS-1$
        String search = "  <a/>\n</close>"; //$NON-NLS-1$
        String replacement = "  <a/>\n  <b/>\n</close>"; //$NON-NLS-1$

        MatchResult result = new FuzzyMatcher().findMatch(search, doc);
        assertTrue(result.isSuccess());
        MatchLocation location = result.getLocation().orElseThrow();

        String updated = doc.substring(0, location.getStartOffset())
                + replacement
                + doc.substring(location.getEndOffset());

        assertEquals("file must end with exactly one closing tag, not two", //$NON-NLS-1$
                1, countOccurrences(updated, "</close>")); //$NON-NLS-1$
        assertTrue("file should end with </close>", updated.endsWith("</close>")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
