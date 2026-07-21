package com.codepilot1c.core.provider.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.model.ToolCall;

public class ContentToolCallFallbackParserTest {

    @Test
    public void extractsBareXmlFunctionCall() {
        String content = """
                <function=read_file>
                <parameter=path>
                бух/src/Configuration/Configuration.mdo
                </parameter>
                </function>
                </tool_call>
                """;

        assertTrue(ContentToolCallFallbackParser.hasToolCallMarkers(content));

        List<ToolCall> calls = ContentToolCallFallbackParser.extractFromContent(content);

        assertEquals(1, calls.size());
        assertEquals("read_file", calls.get(0).getName()); //$NON-NLS-1$
        assertEquals("{\"path\":\"бух/src/Configuration/Configuration.mdo\"}", calls.get(0).getArguments()); //$NON-NLS-1$
        assertEquals("", ContentToolCallFallbackParser.stripToolCallBlocks(content)); //$NON-NLS-1$
    }

    @Test
    public void extractsJsonToolCallWithObjectArguments() {
        String content = """
                <tool_call>
                {"name":"git_inspect","arguments":{"operation":"status"}}
                </tool_call>
                """;

        List<ToolCall> calls = ContentToolCallFallbackParser.extractFromContent(content);

        assertEquals(1, calls.size());
        assertEquals("git_inspect", calls.get(0).getName()); //$NON-NLS-1$
        assertEquals("{\"operation\":\"status\"}", calls.get(0).getArguments()); //$NON-NLS-1$
    }

    @Test
    public void rejectsJsonToolCallWithNonObjectArguments() {
        String content = """
                <tool_call>
                {"name":"git_inspect","arguments":[1,2]}
                </tool_call>
                """;

        assertTrue(ContentToolCallFallbackParser.hasToolCallMarkers(content));
        assertTrue(ContentToolCallFallbackParser.extractFromContent(content).isEmpty());
    }

    @Test
    public void ignoresPlainContent() {
        String content = "Готово, изменений не требуется."; //$NON-NLS-1$

        assertFalse(ContentToolCallFallbackParser.hasToolCallMarkers(content));
        assertTrue(ContentToolCallFallbackParser.extractFromContent(content).isEmpty());
        assertEquals(content, ContentToolCallFallbackParser.stripToolCallBlocks(content));
    }
}
