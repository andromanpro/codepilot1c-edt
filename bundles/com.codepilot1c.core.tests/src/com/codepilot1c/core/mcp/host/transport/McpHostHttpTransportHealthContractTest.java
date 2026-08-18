package com.codepilot1c.core.mcp.host.transport;

import static org.junit.Assert.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.codepilot1c.core.mcp.host.McpContractMetadata;
import com.codepilot1c.core.mcp.host.McpContractMetadataService;
import com.codepilot1c.core.mcp.host.McpHostConfig;
import com.codepilot1c.core.mcp.host.McpHostRequestRouter;
import com.codepilot1c.core.mcp.host.McpReadiness;
import com.codepilot1c.core.mcp.host.McpToolExposurePolicy;
import com.codepilot1c.core.mcp.host.prompt.IMcpPromptProvider;
import com.codepilot1c.core.mcp.model.McpPrompt;
import com.codepilot1c.core.mcp.model.McpPromptResult;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class McpHostHttpTransportHealthContractTest {

    @Test
    public void keepsLivenessAndExposesDeterministicReadinessEndpoint() throws Exception {
        AtomicReference<McpReadiness> readiness = new AtomicReference<>(McpReadiness.available());
        McpContractMetadataService metadataService = new McpContractMetadataService(() ->
            new McpContractMetadata(1, "plugin", "2025.2", "gui", "/workspace", readiness.get()));
        McpHostRequestRouter router = new McpHostRequestRouter(
                new AllowAllExposurePolicy(), List.of(), new EmptyPromptProvider(),
                McpHostConfig.MutationPolicy.ALLOW, metadataService);
        McpHostHttpTransport transport = new McpHostHttpTransport(
                "127.0.0.1", 0,
                new McpHostOAuthService("127.0.0.1", 0, ""),
                router,
                McpHostConfig.AuthMode.NONE);
        try {
            transport.start();
            int port = transport.getBoundPort();
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> liveness = client.send(get(port, "/health"), HttpResponse.BodyHandlers.ofString()); //$NON-NLS-1$
            assertEquals(200, liveness.statusCode());
            assertEquals("ok", liveness.body()); //$NON-NLS-1$

            HttpResponse<String> ready = client.send(get(port, "/health/ready"), HttpResponse.BodyHandlers.ofString()); //$NON-NLS-1$
            assertEquals(200, ready.statusCode());
            assertEquals(Map.of("status", "ready", "ready", Boolean.TRUE), json(ready.body())); //$NON-NLS-1$ //$NON-NLS-2$

            readiness.set(McpReadiness.notReady("EDT runtime services are not ready")); //$NON-NLS-1$
            HttpResponse<String> notReady = client.send(get(port, "/health/ready"), HttpResponse.BodyHandlers.ofString()); //$NON-NLS-1$
            assertEquals(503, notReady.statusCode());
            assertEquals(Map.of(
                    "status", "not_ready", //$NON-NLS-1$
                    "ready", Boolean.FALSE, //$NON-NLS-1$
                    "reason", "EDT runtime services are not ready"), json(notReady.body())); //$NON-NLS-1$

            HttpRequest post = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health/ready")) //$NON-NLS-1$
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(405, client.send(post, HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {
            transport.stop();
        }
    }

    private static HttpRequest get(int port, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(); //$NON-NLS-1$
    }

    private static Map<String, Object> json(String value) {
        return new Gson().fromJson(value, new TypeToken<Map<String, Object>>() { }.getType());
    }

    private static final class AllowAllExposurePolicy implements McpToolExposurePolicy {
        @Override
        public boolean isExposed(String toolName) {
            return true;
        }

        @Override
        public boolean requiresConfirmation(String toolName, Map<String, Object> args) {
            return false;
        }

        @Override
        public boolean isDestructive(String toolName) {
            return false;
        }
    }

    private static final class EmptyPromptProvider implements IMcpPromptProvider {
        @Override
        public List<McpPrompt> listPrompts() {
            return List.of();
        }

        @Override
        public java.util.Optional<McpPromptResult> getPrompt(String name, Map<String, Object> arguments) {
            return java.util.Optional.empty();
        }
    }
}
