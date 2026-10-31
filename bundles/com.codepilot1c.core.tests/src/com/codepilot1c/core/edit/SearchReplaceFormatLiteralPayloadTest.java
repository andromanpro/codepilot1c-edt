package com.codepilot1c.core.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SearchReplaceFormatLiteralPayloadTest {
    private static final String FENCE = "```";
    private final SearchReplaceFormat parser = new SearchReplaceFormat();

    private String block(String before, String after) {
        return "<<<<<<< SEARCH\n" + before + "\n=======\n" + after + "\n>>>>>>> REPLACE";
    }

    private void assertPayload(String payload, String before, String after) {
        assertTrue(parser.hasBlocks(payload));
        var blocks = parser.parse(payload);
        assertEquals(1, blocks.size());
        assertEquals(before, blocks.get(0).getSearchText());
        assertEquals(after, blocks.get(0).getReplaceText());
    }

    @Test public void plainBlockKeepsPayload() {
        assertPayload(block("value = 1;", "value = 2;"), "value = 1;", "value = 2;");
    }

    @Test public void outerFenceDoesNotBelongToPayload() {
        assertPayload(FENCE + "diff\n" + block("value = 1;", "value = 2;") + "\n" + FENCE,
                "value = 1;", "value = 2;");
    }

    @Test public void inlineFenceBelongsToStringLiteral() {
        String before = "marker = \"" + FENCE + "\";";
        String after = "marker = \"" + FENCE + "bsl\";";
        assertPayload(block(before, after), before, after);
    }

    @Test public void wrappedInlineFencesArePreserved() {
        String before = "marker = \"" + FENCE + "bsl\";";
        String after = "marker = \"" + FENCE + "java\";";
        assertPayload(FENCE + "patch\n" + block(before, after) + "\n" + FENCE, before, after);
    }

    @Test public void multipleBlocksKeepInlineFenceAndFollowingBlock() {
        String literal = "marker = \"" + FENCE + "\";";
        String payload = block(literal, literal + " // preserved") + "\n" + block("x = 1;", "x = 2;");
        var blocks = parser.parse(payload);
        assertEquals(2, blocks.size());
        assertEquals(literal, blocks.get(0).getSearchText());
        assertEquals(literal + " // preserved", blocks.get(0).getReplaceText());
        assertEquals("x = 1;", blocks.get(1).getSearchText());
        assertEquals("x = 2;", blocks.get(1).getReplaceText());
    }

    @Test public void crlfNormalizationPreservesFenceCharacters() {
        String before = "marker = \"" + FENCE + "\";\nvalue = 1;";
        String after = "marker = \"" + FENCE + "\";\nvalue = 2;";
        assertPayload(block(before, after).replace("\n", "\r\n"), before, after);
    }

    @Test public void emptyInputRemainsEmpty() {
        assertTrue(parser.parse(null).isEmpty());
        assertTrue(parser.parse("").isEmpty());
    }
}
