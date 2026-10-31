/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.supervisor;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/** Injectable loopback port preflight. */
@FunctionalInterface
public interface PortAvailability {
    boolean available(int port);

    static PortAvailability loopback() {
        return port -> {
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(false);
                socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                return true;
            } catch (IOException exception) {
                return false;
            }
        };
    }
}
