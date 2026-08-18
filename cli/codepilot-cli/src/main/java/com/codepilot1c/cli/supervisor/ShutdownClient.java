/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.supervisor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Best-effort MCP HTTP shutdown/session cleanup boundary. */
@FunctionalInterface
public interface ShutdownClient {
    void request(URI baseUri);

    static ShutdownClient javaHttpClient() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        return baseUri -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/mcp"))
                        .timeout(Duration.ofSeconds(2)).DELETE().build();
                client.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // Process termination below remains the authoritative local stop mechanism.
            }
        };
    }
}
