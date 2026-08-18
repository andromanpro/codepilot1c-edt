/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli;

import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import com.codepilot1c.cli.discovery.EdtInstallationDiscovery;
import com.codepilot1c.cli.platform.HostSystem;
import com.codepilot1c.cli.supervisor.EdtSupervisor;

/** Explicit dependency container for commands and tests. */
public record CliServices(HostSystem host, EdtInstallationDiscovery discovery,
        CliConfiguration configuration, EndpointProbe endpointProbe,
        EdtSupervisor supervisor, PrintWriter out, PrintWriter err, Reader in, String version) {
    /** Compatibility constructor that assembles production supervisor adapters. */
    public CliServices(HostSystem host, EdtInstallationDiscovery discovery,
            CliConfiguration configuration, EndpointProbe endpointProbe,
            PrintWriter out, PrintWriter err, String version) {
        this(host, discovery, configuration, endpointProbe,
                EdtSupervisor.production(host, discovery, endpointProbe), out, err,
                new InputStreamReader(System.in, StandardCharsets.UTF_8), version);
    }

    /** Constructor for commands that need injectable standard input in tests. */
    public CliServices(HostSystem host, EdtInstallationDiscovery discovery,
            CliConfiguration configuration, EndpointProbe endpointProbe,
            PrintWriter out, PrintWriter err, Reader in, String version) {
        this(host, discovery, configuration, endpointProbe,
                EdtSupervisor.production(host, discovery, endpointProbe), out, err, in, version);
    }
}
