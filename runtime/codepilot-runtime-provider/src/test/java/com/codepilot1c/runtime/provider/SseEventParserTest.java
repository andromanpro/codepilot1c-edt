/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.provider;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class SseEventParserTest {

    @Test
    public void parsesLfCrLfCommentsEventsMultilineDataAndFinalPartialFrame() {
        List<SseEventParser.Event> events = new ArrayList<>();
        SseEventParser parser = new SseEventParser(events::add);
        String input = ": keepalive\r\n\r\n"
                + "event: completion\r\n"
                + "data: {\"one\":\r\n"
                + "data: 1}\r\n\r\n"
                + ": another keepalive\n\n"
                + "event: error\n"
                + "data: final";

        for (int index = 0; index < input.length(); index++) {
            char[] one = { input.charAt(index) };
            parser.accept(one, 0, one.length);
        }
        parser.finish();

        assertEquals(List.of(
                new SseEventParser.Event("completion", "{\"one\":\n1}"), //$NON-NLS-1$ //$NON-NLS-2$
                new SseEventParser.Event("error", "final")), events); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void dispatchesEmptyDataFrameButNotBlankOrCommentOnlyFrames() {
        List<SseEventParser.Event> events = new ArrayList<>();
        SseEventParser parser = new SseEventParser(events::add);
        char[] input = "\n: ping\n\ndata:\n\n".toCharArray(); //$NON-NLS-1$

        parser.accept(input, 0, input.length);
        parser.finish();

        assertEquals(List.of(new SseEventParser.Event("message", "")), events); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
