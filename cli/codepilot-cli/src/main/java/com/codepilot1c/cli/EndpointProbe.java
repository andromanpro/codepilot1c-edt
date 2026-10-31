/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Injectable health probe for the local MCP host. */
@FunctionalInterface
public interface EndpointProbe {
    ProbeResult probe(URI endpoint);

    record ProbeResult(boolean reachable, int httpStatus, String detail) { }

    static EndpointProbe javaHttpClient() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        return endpoint -> {
            try {
                URI health = endpoint.resolve("/health/ready");
                HttpRequest request = HttpRequest.newBuilder(health).timeout(Duration.ofSeconds(2)).GET().build();
                int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                return new ProbeResult(status >= 200 && status < 300, status, "HTTP " + status);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return new ProbeResult(false, 0, "interrupted");
            } catch (Exception exception) {
                return new ProbeResult(false, 0, exception.getClass().getSimpleName());
            }
        };
    }
}
