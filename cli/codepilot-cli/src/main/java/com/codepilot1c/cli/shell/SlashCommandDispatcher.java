/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell;

import java.util.Locale;
import java.util.Objects;

/** Strict read-only/control slash-command parser. */
public final class SlashCommandDispatcher {
    public boolean dispatch(String input, Commands commands) throws Exception {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(commands, "commands");
        String trimmed = input.trim();
        if (!trimmed.startsWith("/")) return false;
        String[] parts = trimmed.split("\\s+", 2);
        String name = parts[0].toLowerCase(Locale.ROOT);
        String argument = parts.length == 1 ? "" : parts[1].trim();
        switch (name) {
            case "/help" -> noArgument(name, argument, commands::help);
            case "/new" -> noArgument(name, argument, commands::newSession);
            case "/status" -> noArgument(name, argument, commands::status);
            case "/tools" -> noArgument(name, argument, commands::tools);
            case "/model" -> noArgument(name, argument, commands::model);
            case "/sessions" -> noArgument(name, argument, commands::sessions);
            case "/resume" -> {
                if (argument.isBlank()) commands.error("usage: /resume <session-id>");
                else commands.resume(argument);
            }
            case "/exit" -> noArgument(name, argument, commands::exit);
            default -> commands.error("Unknown command: " + name + ". Type /help.");
        }
        return true;
    }

    private static void noArgument(String command, String argument, CheckedAction action)
            throws Exception {
        if (!argument.isBlank()) throw new CommandUsageException(command + " takes no arguments");
        action.run();
    }

    public interface Commands {
        void help() throws Exception;
        void newSession() throws Exception;
        void status() throws Exception;
        void tools() throws Exception;
        void model() throws Exception;
        void sessions() throws Exception;
        void resume(String id) throws Exception;
        void exit() throws Exception;
        void error(String message);
    }

    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }

    public static final class CommandUsageException extends Exception {
        private static final long serialVersionUID = 1L;
        CommandUsageException(String message) { super(message); }
    }
}
