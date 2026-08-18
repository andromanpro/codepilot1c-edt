package com.codepilot1c.core.provider.config;

import static org.junit.Assert.assertFalse;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.Test;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmRequest;
import com.sun.net.httpserver.HttpServer;

/** Regression coverage for the provider's content-free logging boundary. */
public class DynamicLlmProviderLogSafetyTest {

    @Test
    public void logsOnlyMetadataAndNeverRequestResponseOrConfigurationContent() throws Exception {
        String prompt = "PROMPT_CONTENT_MARKER_7f31"; //$NON-NLS-1$
        String response = "RESPONSE_CONTENT_MARKER_6b42"; //$NON-NLS-1$
        String arguments = "TOOL_ARGUMENT_MARKER_5a53"; //$NON-NLS-1$
        String key = "API_KEY_MARKER_4d64"; //$NON-NLS-1$
        String model = "MODEL_CONTENT_MARKER_3c75"; //$NON-NLS-1$
        String providerName = "PROVIDER_NAME_MARKER_2b86"; //$NON-NLS-1$
        String endpointMarker = "endpoint-marker-1a97"; //$NON-NLS-1$
        String headerName = "X-Custom-Marker-8e08"; //$NON-NLS-1$
        String headerValue = "CUSTOM_HEADER_VALUE_MARKER_9f19"; //$NON-NLS-1$

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); //$NON-NLS-1$
        server.createContext("/" + endpointMarker + "/chat/completions", exchange -> { //$NON-NLS-1$ //$NON-NLS-2$
            exchange.getRequestBody().readAllBytes();
            String body = "{\"choices\":[{\"message\":{\"content\":\"" + response
                    + "\",\"tool_calls\":[{\"id\":\"call\",\"function\":{\"name\":\"tool\","
                    + "\"arguments\":\"{\\\"value\\\":\\\"" + arguments
                    + "\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();

        List<VibeLogger.LogEntry> captured = new ArrayList<>();
        Consumer<VibeLogger.LogEntry> listener = entry -> {
            if (DynamicLlmProvider.class.getSimpleName().equals(entry.getCategory())) captured.add(entry);
        };
        VibeLogger.getInstance().addListener(listener);
        try {
            LlmProviderConfig config = new LlmProviderConfig("log-safe", providerName, //$NON-NLS-1$
                    ProviderType.OPENAI_COMPATIBLE,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/" + endpointMarker, //$NON-NLS-1$ //$NON-NLS-2$
                    key, model, 256);
            config.setCustomHeaders(Map.of(headerName, headerValue));
            DynamicLlmProvider provider = new DynamicLlmProvider(config, ignored -> key, () -> 10);
            provider.complete(LlmRequest.builder().addMessage(LlmMessage.user(prompt)).build())
                    .get(5, TimeUnit.SECONDS);

            String logs = captured.stream().map(VibeLogger.LogEntry::getMessage)
                    .reduce("", (left, right) -> left + "\n" + right); //$NON-NLS-1$ //$NON-NLS-2$
            for (String forbidden : List.of(prompt, response, arguments, key, model, providerName,
                    endpointMarker, headerName, headerValue)) {
                assertFalse("log leaked marker: " + forbidden, logs.contains(forbidden)); //$NON-NLS-1$
            }
        } finally {
            VibeLogger.getInstance().removeListener(listener);
            server.stop(0);
        }
    }
}
