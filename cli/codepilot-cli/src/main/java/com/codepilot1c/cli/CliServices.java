/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli;

import java.io.PrintWriter;

import com.codepilot1c.cli.discovery.EdtInstallationDiscovery;
import com.codepilot1c.cli.platform.HostSystem;
import com.codepilot1c.cli.supervisor.EdtSupervisor;

/** Explicit dependency container for commands and tests. */
public record CliServices(HostSystem host, EdtInstallationDiscovery discovery,
        CliConfiguration configuration, EndpointProbe endpointProbe,
        EdtSupervisor supervisor, PrintWriter out, PrintWriter err, String version) {
    /** Compatibility constructor that assembles production supervisor adapters. */
    public CliServices(HostSystem host, EdtInstallationDiscovery discovery,
            CliConfiguration configuration, EndpointProbe endpointProbe,
            PrintWriter out, PrintWriter err, String version) {
        this(host, discovery, configuration, endpointProbe,
                EdtSupervisor.production(host, discovery, endpointProbe), out, err, version);
    }
}
