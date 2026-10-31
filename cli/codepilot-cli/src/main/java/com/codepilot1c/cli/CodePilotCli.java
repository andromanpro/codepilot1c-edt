/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli;

import java.io.PrintWriter;

import com.codepilot1c.cli.command.RootCommand;
import com.codepilot1c.cli.discovery.EdtInstallationDiscovery;
import com.codepilot1c.cli.platform.DefaultHostSystem;
import com.codepilot1c.cli.platform.HostSystem;

import picocli.CommandLine;

/** Java 17 entry point for the standalone CodePilot CLI harness. */
public final class CodePilotCli {
    private CodePilotCli() { }

    public static void main(String[] args) {
        System.exit(execute(defaultServices(), args));
    }

    public static int execute(CliServices services, String... args) {
        CommandLine commandLine = RootCommand.commandLine(services);
        configure(commandLine, services);
        commandLine.setCaseInsensitiveEnumValuesAllowed(true);
        commandLine.setParameterExceptionHandler((exception, arguments) -> {
            services.err().println("error[usage]: " + exception.getMessage());
            services.err().flush();
            return ExitCodes.USAGE;
        });
        commandLine.setExecutionExceptionHandler((exception, ignored, parseResult) -> {
            services.err().println("error[internal]: " + exception.getClass().getSimpleName());
            services.err().flush();
            return ExitCodes.FAILURE;
        });
        if (args.length == 0) {
            if (services.terminalFactory().isInteractive()) return commandLine.execute("shell");
            commandLine.usage(services.out());
            return ExitCodes.USAGE;
        }
        return commandLine.execute(args);
    }

    private static void configure(CommandLine commandLine, CliServices services) {
        commandLine.setOut(services.out());
        commandLine.setErr(services.err());
        commandLine.getCommandSpec().versionProvider(() -> new String[] { "codepilot " + services.version() });
        commandLine.getSubcommands().values().forEach(child -> configure(child, services));
    }

    private static CliServices defaultServices() {
        HostSystem host = new DefaultHostSystem();
        return new CliServices(host, new EdtInstallationDiscovery(host), new CliConfiguration(host),
                EndpointProbe.javaHttpClient(), new PrintWriter(System.out, true), new PrintWriter(System.err, true), version());
    }

    private static String version() {
        String implementationVersion = CodePilotCli.class.getPackage().getImplementationVersion();
        return implementationVersion == null ? "development" : implementationVersion;
    }
}
