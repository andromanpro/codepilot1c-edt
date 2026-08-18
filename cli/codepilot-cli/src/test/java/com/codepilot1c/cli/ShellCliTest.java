/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

import org.junit.Test;

import com.codepilot1c.cli.discovery.EdtInstallationDiscovery;
import com.codepilot1c.cli.shell.JLineTerminalFactory;
import com.codepilot1c.cli.shell.ShellTerminal;
import com.codepilot1c.cli.shell.TerminalFactory;

public class ShellCliTest {
    private static final String ROOT_HELP = """
            Usage: codepilot [-hV] [--output=<outputMode>] [COMMAND]
            Standalone harness for CodePilot and 1C:EDT.
              -h, --help      Show this help message and exit.
                  --output=<outputMode>
                              Output format: TEXT, JSON.
              -V, --version   Print version information and exit.
            Commands:
              version  Print CLI version.
              doctor   Run machine-readable CLI and EDT checks.
              edt      Inspect or control the local EDT host.
              mcp      Call the MCP endpoint exposed by a running EDT host.
              agent    Run the standalone provider-neutral agent loop.
            """;

    @Test public void scriptedDumbTerminalSupportsHelpAndExit() {
        ByteArrayOutputStream terminalOutput = new ByteArrayOutputStream();
        TerminalFactory terminalFactory = new JLineTerminalFactory(
                new ByteArrayInputStream("/help\n/exit\n".getBytes(StandardCharsets.UTF_8)),
                terminalOutput, () -> false);
        Fixture fixture = new Fixture(terminalFactory);

        assertEquals(ExitCodes.OK, fixture.execute("shell"));
        String text = terminalOutput.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(text.contains("CodePilot shell (foundation)"));
        assertTrue(text.contains("codepilot> "));
        assertTrue(text.contains("Commands: /help, /exit"));
    }

    @Test public void scriptedDumbTerminalTreatsEofAsCleanExit() {
        ByteArrayOutputStream terminalOutput = new ByteArrayOutputStream();
        TerminalFactory terminalFactory = new JLineTerminalFactory(
                new ByteArrayInputStream(new byte[0]), terminalOutput, () -> false);

        assertEquals(ExitCodes.OK, new Fixture(terminalFactory).execute("shell"));
        assertTrue(terminalOutput.toString(StandardCharsets.UTF_8)
                .contains("CodePilot shell (foundation)"));
    }

    @Test public void zeroArgumentsRouteByTtyWithoutOpeningNonTtyTerminal() {
        ScriptedTerminal nonTty = new ScriptedTerminal();
        Fixture nonTtyFixture = new Fixture(new ScriptedFactory(false, nonTty));
        assertEquals(ExitCodes.USAGE, nonTtyFixture.execute());
        assertTrue(nonTtyFixture.out().startsWith("Usage: codepilot"));
        assertFalse(nonTty.opened);

        ScriptedTerminal tty = new ScriptedTerminal((String) null);
        Fixture ttyFixture = new Fixture(new ScriptedFactory(true, tty));
        assertEquals(ExitCodes.OK, ttyFixture.execute());
        assertTrue(tty.opened);
        assertTrue(tty.closed);
    }

    @Test public void rootHelpRemainsByteCompatibleAndDoesNotOpenTerminal() {
        ScriptedTerminal terminal = new ScriptedTerminal();
        Fixture fixture = new Fixture(new ScriptedFactory(true, terminal));

        assertEquals(ExitCodes.OK, fixture.execute("--help"));
        assertEquals(ROOT_HELP, fixture.out());
        assertFalse(terminal.opened);
    }

    @Test public void explicitShellWorksWithoutTtyAndAcceptsReservedOptions() {
        ScriptedTerminal terminal = new ScriptedTerminal("/exit");
        Fixture fixture = new Fixture(new ScriptedFactory(false, terminal));

        assertEquals(ExitCodes.OK, fixture.execute("shell", "--mode", "connected",
                "--instance-id", "instance", "--endpoint", "http://localhost:8765",
                "--provider", "openai-compatible", "--provider-endpoint", "http://localhost:1234",
                "--model", "model",
                "--provider-api-key-file", "secret", "--provider-allow-insecure-http",
                "--max-steps", "7", "--turn-timeout", "12", "--system-prompt-file", "system.md"));
        assertTrue(terminal.opened);
        assertTrue(terminal.closed);
    }

    @Test public void terminalIsClosedWhenReadingFails() {
        ScriptedTerminal terminal = new ScriptedTerminal();
        terminal.readFailure = new IllegalStateException("boom");
        Fixture fixture = new Fixture(new ScriptedFactory(false, terminal));

        assertEquals(ExitCodes.FAILURE, fixture.execute("shell"));
        assertTrue(terminal.closed);
        assertEquals("error[internal]: IllegalStateException\n", fixture.err());
    }

    @Test public void invalidTurnBoundsUseStableUsageExitWithoutOpeningTerminal() {
        ScriptedTerminal terminal = new ScriptedTerminal();
        Fixture fixture = new Fixture(new ScriptedFactory(false, terminal));

        assertEquals(ExitCodes.USAGE, fixture.execute("shell", "--max-steps", "0"));
        assertFalse(terminal.opened);
        assertEquals("error[usage]: invalid max steps\n", fixture.err());
    }

    private static final class Fixture {
        private final TerminalFactory terminalFactory;
        private final StringWriter output = new StringWriter();
        private final StringWriter errors = new StringWriter();
        private final FakeHostSystem host = new FakeHostSystem();

        private Fixture(TerminalFactory terminalFactory) {
            this.terminalFactory = terminalFactory;
        }

        int execute(String... args) {
            CliServices services = new CliServices(host, new EdtInstallationDiscovery(host),
                    new CliConfiguration(host), endpoint -> new EndpointProbe.ProbeResult(true, 200, "HTTP 200"),
                    new PrintWriter(output, true), new PrintWriter(errors, true), new StringReader(""), "9.8.7",
                    terminalFactory);
            return CodePilotCli.execute(services, args);
        }

        String out() { return output.toString().replace("\r\n", "\n"); }
        String err() { return errors.toString().replace("\r\n", "\n"); }
    }

    private static final class ScriptedFactory implements TerminalFactory {
        private final boolean interactive;
        private final ScriptedTerminal terminal;

        private ScriptedFactory(boolean interactive, ScriptedTerminal terminal) {
            this.interactive = interactive;
            this.terminal = terminal;
        }

        @Override public boolean isInteractive() { return interactive; }
        @Override public ShellTerminal open() {
            terminal.opened = true;
            return terminal;
        }
    }

    private static final class ScriptedTerminal implements ShellTerminal {
        private final Deque<String> lines = new ArrayDeque<>();
        private boolean eof;
        private boolean opened;
        private boolean closed;
        private RuntimeException readFailure;

        private ScriptedTerminal(String... lines) {
            if (lines.length == 1 && lines[0] == null) {
                eof = true;
            } else {
                this.lines.addAll(Arrays.asList(lines));
            }
        }

        @Override public String readLine(String prompt) {
            if (readFailure != null) throw readFailure;
            if (lines.isEmpty()) {
                if (eof) return null;
                throw new AssertionError("script exhausted without EOF");
            }
            return lines.removeFirst();
        }

        @Override public void println(String text) { }
        @Override public void flush() { }
        @Override public void close() throws IOException { closed = true; }
    }
}
