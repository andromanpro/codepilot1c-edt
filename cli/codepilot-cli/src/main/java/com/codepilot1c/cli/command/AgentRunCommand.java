/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.codepilot1c.cli.ExitCodes;
import com.codepilot1c.runtime.agent.AgentError;
import com.codepilot1c.runtime.agent.AgentMessage;
import com.codepilot1c.runtime.agent.AgentRequest;
import com.codepilot1c.runtime.agent.AgentResult;
import com.codepilot1c.runtime.agent.AgentRunConfig;
import com.codepilot1c.runtime.agent.AgentRuntime;
import com.codepilot1c.runtime.agent.CancellationSource;
import com.codepilot1c.runtime.agent.McpToolRuntime;
import com.codepilot1c.runtime.agent.OpenAiCompatibleAgentModel;
import com.codepilot1c.runtime.agent.ToolDefinition;
import com.codepilot1c.runtime.agent.ToolExecutionResult;
import com.codepilot1c.runtime.agent.ToolRuntime;
import com.codepilot1c.runtime.mcp.McpClientException;
import com.codepilot1c.runtime.provider.ProviderConfiguration;
import com.codepilot1c.runtime.provider.RuntimeProviderFactory;
import com.google.gson.JsonObject;

import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** One-shot CLI host for runtime-agent + runtime-provider + optional runtime-mcp-client tools. */
@Command(name = "run", mixinStandardHelpOptions = true,
        description = "Run one bounded agent request.")
final class AgentRunCommand implements java.util.concurrent.Callable<Integer>, McpConnectionOptions {
    private static final long MAX_PROMPT_BYTES = 1024 * 1024;
    private static final long MAX_SECRET_FILE_BYTES = 64 * 1024;
    private static final int MAX_ALLOWED_STEPS = 1000;
    private static final long MAX_TIMEOUT_SECONDS = 86_400;

    private final RootCommand root;

    @ArgGroup(exclusive = true, multiplicity = "1")
    private PromptSource promptSource;

    @Option(names = "--provider-endpoint",
            description = "OpenAI-compatible base endpoint (flag > property > environment).")
    private String providerEndpoint;
    @Option(names = "--model",
            description = "Provider model (flag > property > environment).")
    private String model;
    @Option(names = "--provider-api-key-file",
            description = "Private UTF-8 file containing the provider API key.")
    private String providerApiKeyFile;
    @Option(names = "--provider-allow-insecure-http",
            description = "Allow a non-loopback plain HTTP provider endpoint.")
    private boolean providerAllowInsecureHttp;

    @Option(names = "--max-steps", defaultValue = "16",
            description = "Maximum model steps (default: ${DEFAULT-VALUE}).")
    private int maxSteps;
    @Option(names = "--timeout", defaultValue = "300",
            description = "Global timeout in seconds, including MCP discovery (default: ${DEFAULT-VALUE}).")
    private long timeoutSeconds;

    @Option(names = "--no-tools", description = "Run without connecting to MCP or exposing tools.")
    private boolean noTools;
    @Option(names = "--mcp-endpoint",
            description = "MCP endpoint or host base URL; mutually exclusive with --instance-id.")
    private String mcpEndpoint;
    @Option(names = "--instance-id",
            description = "Use this registered EDT instance UUID; mutually exclusive with --mcp-endpoint.")
    private String instanceId;
    @Option(names = "--allow-insecure-http",
            description = "Allow a non-loopback plain HTTP MCP endpoint.")
    private boolean allowInsecureHttp;
    @Option(names = "--mcp-bearer-token-file",
            description = "UTF-8 file containing the MCP bearer token.")
    private String mcpBearerTokenFile;

    AgentRunCommand(RootCommand root) { this.root = root; }

    @Override public Integer call() {
        final String prompt;
        final ProviderSetup provider;
        try {
            validateBounds();
            validateToolOptions();
            prompt = readPrompt();
            provider = providerConfiguration();
        } catch (AgentUsageException failure) {
            return usage(failure.code());
        }

        long deadline = deadline(timeoutSeconds);
        CancellationSource cancellation = new CancellationSource();
        Thread shutdownHook = null;
        boolean hookInstalled = false;
        boolean interrupted = false;
        ExactSecretRedactor redactor = null;
        try {
            redactor = ExactSecretRedactor.of(provider.apiKey());
            shutdownHook = new Thread(cancellation::cancel, "codepilot-agent-cancel");
            hookInstalled = installShutdownHook(shutdownHook);
            ToolRuntime tools = NoToolsRuntime.INSTANCE;
            McpCommandSupport.Connection connection = null;
            try {
                if (!noTools) {
                    connection = McpCommandSupport.connect(root, this);
                    ExactSecretRedactor combined = ExactSecretRedactor.combine(redactor, connection.redactor());
                    redactor.close();
                    redactor = combined;
                    tools = awaitMcpTools(connection, cancellation, deadline);
                }
                Duration remaining = remaining(deadline);
                if (remaining == null) return terminalWithoutRun("timed_out", "timeout", 0, redactor);

                var modelAdapter = new OpenAiCompatibleAgentModel(
                        new RuntimeProviderFactory().create(provider.configuration()));
                try (AgentRuntime runtime = new AgentRuntime(modelAdapter, tools,
                        new AgentRunConfig(maxSteps, remaining))) {
                    AgentRequest request = new AgentRequest(UUID.randomUUID().toString(),
                            List.of(new AgentMessage.Text(AgentMessage.Role.USER, prompt)));
                    CompletableFuture<AgentResult> run = runtime.run(request, cancellation);
                    AgentResult result;
                    try {
                        result = run.get();
                    } catch (InterruptedException interrupt) {
                        interrupted = true;
                        cancellation.cancel();
                        Thread.interrupted();
                        result = run.get();
                    }
                    return printResult(result, redactor);
                }
            } catch (InterruptedException interrupt) {
                interrupted = true;
                cancellation.cancel();
                Thread.interrupted();
                return terminalWithoutRun("cancelled", "cancelled", 0, redactor);
            } catch (TimeoutException timeout) {
                cancellation.cancel();
                return terminalWithoutRun("timed_out", "timeout", 0, redactor);
            } catch (CancellationException cancelled) {
                return terminalWithoutRun("cancelled", "cancelled", 0, redactor);
            } catch (ExecutionException failure) {
                return connectionFailure(unwrap(failure), redactor);
            } catch (McpCommandSupport.McpUsageException failure) {
                return usage(failure.code(), redactor);
            } catch (RuntimeException failure) {
                return connectionFailure(unwrap(failure), redactor);
            } finally {
                if (connection != null) connection.close();
            }
        } finally {
            if (hookInstalled && shutdownHook != null) removeShutdownHook(shutdownHook);
            if (interrupted) Thread.currentThread().interrupt();
            if (redactor != null) redactor.close();
            provider.close();
        }
    }

    private ToolRuntime awaitMcpTools(McpCommandSupport.Connection connection,
            CancellationSource cancellation, long deadline)
            throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<McpToolRuntime> future = McpToolRuntime.connect(connection.client()).toCompletableFuture();
        while (true) {
            if (cancellation.isCancelled()) {
                future.cancel(true);
                throw new CancellationException();
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                future.cancel(true);
                throw new TimeoutException();
            }
            try {
                return future.get(Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(100)),
                        TimeUnit.NANOSECONDS);
            } catch (TimeoutException waitAgain) {
                if (remainingNanos <= TimeUnit.MILLISECONDS.toNanos(100)) throw waitAgain;
            }
        }
    }

    private ProviderSetup providerConfiguration() {
        String endpoint = first(providerEndpoint,
                root.services().host().systemProperty("codepilot.provider.endpoint"),
                root.services().host().environment("CODEPILOT_PROVIDER_ENDPOINT"));
        String selectedModel = first(model,
                root.services().host().systemProperty("codepilot.provider.model"),
                root.services().host().environment("CODEPILOT_PROVIDER_MODEL"));
        if (endpoint == null) throw new AgentUsageException("provider_endpoint_required");
        if (selectedModel == null) throw new AgentUsageException("provider_model_required");
        URI providerUri = validatedProviderEndpoint(endpoint);

        char[] apiKey = readProviderApiKey();
        boolean success = false;
        try {
            ProviderConfiguration configuration = ProviderConfiguration.builder()
                    .id("cli-openai-compatible")
                    .displayName("CLI OpenAI-compatible provider")
                    .baseUri(providerUri)
                    .defaultModel(selectedModel)
                    .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 30)))
                    // The agent deadline owns the global terminal reason; this is only a backstop.
                    .requestTimeout(Duration.ofSeconds(timeoutSeconds + 1))
                    .apiKey(apiKey)
                    .build();
            ProviderSetup setup = new ProviderSetup(configuration, apiKey);
            success = true;
            return setup;
        } catch (RuntimeException failure) {
            if (failure instanceof AgentUsageException usage) throw usage;
            throw new AgentUsageException("invalid_provider_configuration");
        } finally {
            if (!success) Arrays.fill(apiKey, '\0');
        }
    }

    private char[] readProviderApiKey() {
        String value;
        if (providerApiKeyFile != null) {
            try {
                return PrivateUtf8SecretReader.read(Path.of(providerApiKeyFile),
                        Math.toIntExact(MAX_SECRET_FILE_BYTES));
            } catch (RuntimeException failure) {
                throw new AgentUsageException("provider_api_key_file_unreadable");
            }
        } else {
            value = first(null, root.services().host().systemProperty("codepilot.provider.apiKey"),
                    root.services().host().environment("CODEPILOT_PROVIDER_API_KEY"));
        }
        return value == null ? new char[0] : value.toCharArray();
    }

    private String readPrompt() {
        if (promptSource.inline != null) return promptSource.inline;
        if (promptSource.file != null) return readTextFile(promptSource.file, MAX_PROMPT_BYTES,
                "prompt_file_unreadable");
        String value;
        try {
            value = McpCommandSupport.readStandardInput(root);
        } catch (McpCommandSupport.McpUsageException failure) {
            throw new AgentUsageException("prompt_stdin_unreadable");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_PROMPT_BYTES) {
            throw new AgentUsageException("prompt_too_large");
        }
        return value;
    }

    private String readTextFile(String value, long maximumBytes, String code) {
        try {
            Path path = Path.of(value);
            if (!Files.isRegularFile(path) || Files.size(path) > maximumBytes) {
                throw new AgentUsageException(code);
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (AgentUsageException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new AgentUsageException(code);
        }
    }

    private void validateBounds() {
        if (maxSteps <= 0 || maxSteps > MAX_ALLOWED_STEPS) {
            throw new AgentUsageException("invalid_max_steps");
        }
        if (timeoutSeconds <= 0 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new AgentUsageException("invalid_timeout");
        }
    }

    private void validateToolOptions() {
        if (noTools && (mcpEndpoint != null || instanceId != null || allowInsecureHttp
                || mcpBearerTokenFile != null)) {
            throw new AgentUsageException("no_tools_mcp_options_conflict");
        }
    }

    private URI validatedProviderEndpoint(String value) {
        final URI endpoint;
        try {
            endpoint = URI.create(value);
        } catch (IllegalArgumentException failure) {
            throw new AgentUsageException("invalid_provider_configuration");
        }
        if (!endpoint.isAbsolute() || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !("http".equalsIgnoreCase(endpoint.getScheme())
                        || "https".equalsIgnoreCase(endpoint.getScheme()))) {
            throw new AgentUsageException("invalid_provider_configuration");
        }
        if ("http".equalsIgnoreCase(endpoint.getScheme()) && !isLoopback(endpoint.getHost())
                && !providerAllowInsecureHttp) {
            throw new AgentUsageException("insecure_provider_endpoint");
        }
        return endpoint;
    }

    private boolean isLoopback(String host) {
        if (host == null) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if ("localhost".equals(normalized) || "::1".equals(normalized)) return true;
        String[] octets = normalized.split("\\.", -1);
        if (octets.length != 4) return false;
        try {
            if (Integer.parseInt(octets[0]) != 127) return false;
            for (int index = 1; index < octets.length; index++) {
                int octet = Integer.parseInt(octets[index]);
                if (octet < 0 || octet > 255) return false;
            }
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private int printResult(AgentResult result, ExactSecretRedactor redactor) {
        String status = result.status().name().toLowerCase(Locale.ROOT);
        String reason = result.error().map(error -> error.code().name().toLowerCase(Locale.ROOT))
                .orElse("completed");
        String text = result.text().orElse(null);
        Map<String, Object> output = terminal(status, reason, result.steps());
        if (text != null) output.put("text", text);
        CommandOutput.print(root, terminalText(status, reason, result.steps(), text), output, redactor);
        if (result.status() == AgentResult.Status.COMPLETED) return ExitCodes.OK;
        if (result.error().map(AgentError::code).orElse(null) == AgentError.Code.PROVIDER_AUTH) {
            return ExitCodes.AUTH;
        }
        return ExitCodes.FAILURE;
    }

    private int terminalWithoutRun(String status, String reason, int steps, ExactSecretRedactor redactor) {
        CommandOutput.print(root, terminalText(status, reason, steps, null),
                terminal(status, reason, steps), redactor);
        return ExitCodes.FAILURE;
    }

    private int connectionFailure(Throwable failure, ExactSecretRedactor redactor) {
        McpClientException mcp = findMcpFailure(failure);
        if (mcp == null) {
            return terminalWithoutRun("failed", "agent_setup_failed", 0, redactor);
        }
        boolean auth = mcp.httpStatus() == 401 || mcp.httpStatus() == 403;
        String reason = auth ? "authentication_failed" : "mcp_unavailable";
        Map<String, Object> output = terminal("failed", reason, 0);
        output.put("failureKind", mcp.kind().name().toLowerCase(Locale.ROOT));
        if (mcp.httpStatus() >= 0) output.put("httpStatus", mcp.httpStatus());
        CommandOutput.print(root, terminalText("failed", reason, 0, null), output, redactor);
        return auth ? ExitCodes.AUTH : ExitCodes.EDT_UNAVAILABLE;
    }

    private int usage(String code) {
        Map<String, Object> output = terminal("failed", code, 0);
        output.put("error", code);
        CommandOutput.print(root, "error[usage]: " + code, output);
        return ExitCodes.USAGE;
    }

    private int usage(String code, ExactSecretRedactor redactor) {
        Map<String, Object> output = terminal("failed", code, 0);
        output.put("error", code);
        CommandOutput.print(root, "error[usage]: " + code, output, redactor);
        return ExitCodes.USAGE;
    }

    private Map<String, Object> terminal(String status, String reason, int steps) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("command", "agent run");
        output.put("status", status);
        output.put("terminalReason", reason);
        output.put("steps", steps);
        return output;
    }

    private String terminalText(String status, String reason, int steps, String text) {
        String header = "status=" + status + " reason=" + reason + " steps=" + steps;
        return text == null ? header : header + System.lineSeparator() + text;
    }

    private static long deadline(long seconds) {
        long now = System.nanoTime();
        long delta = TimeUnit.SECONDS.toNanos(seconds);
        return Long.MAX_VALUE - now < delta ? Long.MAX_VALUE : now + delta;
    }

    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        return nanos <= 0 ? null : Duration.ofNanos(nanos);
    }

    private static String first(String flag, String property, String environment) {
        if (flag != null && !flag.isBlank()) return flag.trim();
        if (property != null && !property.isBlank()) return property.trim();
        return environment == null || environment.isBlank() ? null : environment.trim();
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof ExecutionException
                || current instanceof java.util.concurrent.CompletionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static McpClientException findMcpFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof McpClientException value) return value;
            current = current.getCause();
        }
        return null;
    }

    private static boolean installShutdownHook(Thread hook) {
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            return true;
        } catch (IllegalStateException | SecurityException ignored) {
            return false;
        }
    }

    private static void removeShutdownHook(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException | SecurityException ignored) {
            // JVM shutdown already owns the hook.
        }
    }

    @Override public String endpoint() { return mcpEndpoint; }
    @Override public String instanceId() { return instanceId; }
    @Override public boolean allowInsecureHttp() { return allowInsecureHttp; }
    @Override public String bearerTokenFile() { return mcpBearerTokenFile; }

    static final class PromptSource {
        @Option(names = "--prompt", description = "Inline prompt.") String inline;
        @Option(names = "--prompt-file", description = "Read the UTF-8 prompt from a file.") String file;
        @Option(names = "--prompt-stdin", description = "Read the prompt from standard input.") boolean stdin;
    }

    /** Owns the CLI source copy; the current runtime configuration owns its separate provider-lifetime copy. */
    private record ProviderSetup(ProviderConfiguration configuration, char[] apiKey) implements AutoCloseable {
        ProviderSetup {
            apiKey = apiKey == null ? new char[0] : apiKey;
        }
        @Override public char[] apiKey() { return apiKey; }
        @Override public void close() { Arrays.fill(apiKey, '\0'); }
    }

    private enum NoToolsRuntime implements ToolRuntime {
        INSTANCE;
        @Override public List<ToolDefinition> tools() { return List.of(); }
        @Override public java.util.concurrent.CompletionStage<ToolExecutionResult> execute(
                String name, JsonObject arguments, com.codepilot1c.runtime.agent.CancellationToken cancellation) {
            return CompletableFuture.completedFuture(ToolExecutionResult.failure(
                    "UNKNOWN_TOOL", "Requested tool is not available"));
        }
    }

    private static final class AgentUsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;
        AgentUsageException(String code) { this.code = code; }
        String code() { return code; }
    }
}
