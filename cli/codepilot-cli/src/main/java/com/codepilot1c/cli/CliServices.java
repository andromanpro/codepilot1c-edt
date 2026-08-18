/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli;

import java.io.PrintWriter;

import com.codepilot1c.cli.discovery.EdtInstallationDiscovery;
import com.codepilot1c.cli.platform.HostSystem;

/** Explicit dependency container for commands and tests. */
public record CliServices(HostSystem host, EdtInstallationDiscovery discovery,
        CliConfiguration configuration, EndpointProbe endpointProbe,
        PrintWriter out, PrintWriter err, String version) { }
