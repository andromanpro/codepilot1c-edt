/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import com.google.gson.JsonObject;

import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Executes one MCP tool call using one explicitly supplied JSON object. */
@Command(name = "call", mixinStandardHelpOptions = true,
        description = "Initialize a session and call a tool with a JSON object of arguments.")
final class McpCallCommand implements Callable<Integer> {
    static final class Arguments {
        @Option(names = "--args", paramLabel = "JSON", description = "Inline JSON object arguments.") String inline;
        @Option(names = "--args-file", paramLabel = "FILE", description = "UTF-8 file containing a JSON object.") String file;
        @Option(names = "--args-stdin", description = "Read one JSON object from standard input.") boolean stdin;
    }

    private final RootCommand root; private final McpCommand options;
    @Parameters(index = "0", paramLabel = "TOOL", description = "Name returned by `mcp tools`.") private String tool;
    @ArgGroup(exclusive = true, multiplicity = "0..1") private Arguments arguments;

    McpCallCommand(RootCommand root, McpCommand options) { this.root = root; this.options = options; }

    @Override public Integer call() {
        try {
            JsonObject value;
            if (arguments == null) value = new JsonObject();
            else if (arguments.inline != null) value = McpCommandSupport.parseArguments(arguments.inline);
            else if (arguments.file != null) value = McpCommandSupport.parseArguments(
                    McpCommandSupport.readUtf8(Path.of(arguments.file)));
            else if (arguments.stdin) value = McpCommandSupport.parseArguments(McpCommandSupport.readStandardInput(root));
            else value = new JsonObject();
            return McpCommandSupport.call(root, options, tool, value);
        } catch (McpCommandSupport.McpUsageException exception) {
            return McpCommandSupport.usage(root, "mcp call", exception.code());
        } catch (RuntimeException exception) {
            return McpCommandSupport.usage(root, "mcp call", "arguments_file_unreadable");
        }
    }
}
