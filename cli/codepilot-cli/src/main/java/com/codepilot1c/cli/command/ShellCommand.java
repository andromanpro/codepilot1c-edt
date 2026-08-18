/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

import com.codepilot1c.cli.ExitCodes;
import com.codepilot1c.cli.shell.McpShellToolSession;
import com.codepilot1c.cli.shell.ModeResolver;
import com.codepilot1c.cli.shell.ModeResolver.Candidate;
import com.codepilot1c.cli.shell.ProcessInterruptRegistration;
import com.codepilot1c.cli.shell.ShellController;
import com.codepilot1c.cli.shell.ShellEnvironment;
import com.codepilot1c.cli.shell.ShellInputHandler;
import com.codepilot1c.cli.shell.ShellOptions;
import com.codepilot1c.cli.shell.ShellOptions.Mode;
import com.codepilot1c.cli.shell.ShellSecretRedactor;
import com.codepilot1c.cli.shell.ShellTerminal;
import com.codepilot1c.cli.shell.broker.BrokerClient;
import com.codepilot1c.cli.shell.broker.BrokerInfo;
import com.codepilot1c.cli.shell.broker.BrokeredAgentModel;
import com.codepilot1c.cli.shell.session.SessionStore;
import com.codepilot1c.cli.supervisor.DefaultSupervisorFileSystem;
import com.codepilot1c.cli.supervisor.InstanceRecord;
import com.codepilot1c.cli.supervisor.InstanceRegistry;
import com.codepilot1c.runtime.agent.OpenAiCompatibleAgentModel;
import com.codepilot1c.runtime.mcp.McpClient;
import com.codepilot1c.runtime.mcp.McpClientConfig;
import com.codepilot1c.runtime.provider.ProviderConfiguration;
import com.codepilot1c.runtime.provider.OpenAiCompatibleProvider;
import com.codepilot1c.runtime.provider.RuntimeProviderFactory;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Interactive multi-turn shell entry point. */
@Command(name = "shell", mixinStandardHelpOptions = true, hidden = true,
        description = "Open the interactive CodePilot shell.")
public final class ShellCommand implements Callable<Integer> {
    private static final long MAX_TEXT_FILE_BYTES = 1024 * 1024;
    private static final int MAX_SECRET_FILE_BYTES = 64 * 1024;

    private final RootCommand root;
    /** Retained only for C1 source compatibility in focused tests. */
    private final ShellInputHandler legacyInputHandler;

    @Option(names = "--mode", defaultValue = "AUTO",
            description = "Shell mode: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
    private Mode mode;
    @Option(names = { "--instance-id", "--instance" }, description = "EDT instance UUID.")
    private String instanceId;
    @Option(names = { "--mcp-endpoint", "--endpoint" }, description = "MCP endpoint URL.")
    private String mcpEndpoint;
    @Option(names = "--mcp-bearer-token-file", description = "MCP bearer-token file.")
    private String mcpBearerTokenFile;
    @Option(names = "--allow-insecure-http", description = "Allow non-loopback MCP HTTP.")
    private boolean allowInsecureHttp;
    @Option(names = "--provider", description = "Provider identifier (openai-compatible).")
    private String provider;
    @Option(names = "--provider-endpoint", description = "Provider base endpoint.")
    private String providerEndpoint;
    @Option(names = "--model", description = "Provider model (startup selection only).")
    private String model;
    @Option(names = "--provider-api-key-file", description = "Provider API-key file.")
    private String providerApiKeyFile;
    @Option(names = "--provider-allow-insecure-http", description = "Allow non-loopback provider HTTP.")
    private boolean providerAllowInsecureHttp;
    @Option(names = "--max-steps", defaultValue = "16", description = "Maximum steps per turn.")
    private int maxSteps;
    @Option(names = "--turn-timeout", defaultValue = "300",
            description = "Per-turn timeout in seconds.")
    private long turnTimeoutSeconds;
    @Option(names = "--system-prompt-file", description = "UTF-8 system-prompt file.")
    private String systemPromptFile;

    public ShellCommand(RootCommand root) { this(root, null); }

    public ShellCommand(RootCommand root, ShellInputHandler inputHandler) {
        this.root = root;
        this.legacyInputHandler = inputHandler;
    }

    @Override public Integer call() throws Exception {
        ShellOptions options = options();
        if (legacyInputHandler != null) return legacy(options);
        try { validateBounds(); }
        catch (IllegalArgumentException failure) {
            root.services().err().println("error[usage]: " + failure.getMessage());
            root.services().err().flush();
            return ExitCodes.USAGE;
        }
        try (ShellSecretRedactor redactor = new ShellSecretRedactor();
                ShellTerminal terminal = root.services().terminalFactory().open()) {
            SessionStore store = new SessionStore(
                    SessionStore.defaultRoot(Path.of(root.services().host().userHome())), redactor,
                    warning -> terminal.println(redactor.apply(warning)));
            ModeResolver resolver = resolver(redactor);
            try (ShellController controller = new ShellController(terminal, options,
                    resolver::resolve, store, this::readSystemPrompt, redactor,
                    root.services().host().environment("NO_COLOR") != null);
                    ProcessInterruptRegistration interrupts =
                            ProcessInterruptRegistration.install(controller::interrupt)) {
                return controller.run();
            }
        }
    }

    private int legacy(ShellOptions options) throws Exception {
        try (ShellTerminal terminal = root.services().terminalFactory().open()) {
            terminal.println("CodePilot shell (foundation)");
            terminal.println("Type /help for commands or /exit to leave.");
            terminal.flush();
            while (true) {
                String input = terminal.readLine("codepilot> ");
                if (input == null || "/exit".equals(input.trim())) return ExitCodes.OK;
                if ("/help".equals(input.trim())) terminal.println("Commands: /help, /exit");
                else legacyInputHandler.handle(input, options, terminal);
                terminal.flush();
            }
        }
    }

    private ModeResolver resolver(ShellSecretRedactor redactor) {
        return new ModeResolver(this::discoverCandidates,
                (candidate, options) -> connected(candidate, options, redactor),
                new ModeResolver.StandaloneFactory() {
                    @Override public boolean usable(ShellOptions options) {
                        return standaloneValues() != null;
                    }
                    @Override public ShellEnvironment connect(ShellOptions options,
                            List<Candidate> candidates) throws Exception {
                        return standalone(options, candidates, redactor);
                    }
                });
    }

    private List<Candidate> discoverCandidates(ShellOptions options) {
        if (options.mcpEndpoint() != null && options.instanceId() != null) {
            throw new IllegalArgumentException("MCP endpoint and instance are mutually exclusive");
        }
        if (options.mcpEndpoint() != null) {
            URI endpoint = normalizedMcp(URI.create(options.mcpEndpoint()));
            return List.of(new Candidate(endpoint.toASCIIString(), "unregistered", "explicit endpoint"));
        }
        Path directory = Path.of(root.services().host().userHome(), ".codepilot1c", "instances");
        InstanceRegistry registry = new InstanceRegistry(new DefaultSupervisorFileSystem(), directory);
        if (options.instanceId() != null) {
            try {
                InstanceRecord selected = registry.find(options.instanceId())
                        .orElseThrow(() -> new IllegalArgumentException("instance not found"));
                URI endpoint = normalizedMcp(URI.create(selected.baseUrl()));
                return List.of(new Candidate(endpoint.toASCIIString(), selected.instanceId(),
                        "selected EDT instance"));
            } catch (IOException failure) {
                throw new IllegalArgumentException("instance registry unavailable");
            }
        }

        Map<String, Candidate> candidates = new LinkedHashMap<>();
        try {
            registry.list().stream().sorted(Comparator.comparing(InstanceRecord::startedAt).reversed())
                    .forEach(record -> {
                        URI endpoint = normalizedMcp(URI.create(record.baseUrl()));
                        candidates.putIfAbsent(endpoint.toASCIIString(), new Candidate(
                                endpoint.toASCIIString(), record.instanceId(), "registered EDT instance"));
                    });
        } catch (IOException | RuntimeException ignored) {
            // The configured endpoint remains a deterministic fallback.
        }
        try {
            URI endpoint = normalizedMcp(root.services().configuration().endpoint());
            candidates.putIfAbsent(endpoint.toASCIIString(), new Candidate(
                    endpoint.toASCIIString(), "unregistered", "configured endpoint"));
        } catch (URISyntaxException | RuntimeException ignored) {
            // ModeResolver will produce the actionable no-candidate diagnostic.
        }
        return List.copyOf(candidates.values());
    }

    private ShellEnvironment connected(Candidate candidate, ShellOptions options,
            ShellSecretRedactor redactor) throws Exception {
        URI endpoint = URI.create(candidate.endpoint());
        char[] token = readMcpToken();
        if (token != null) redactor.add(token);
        McpFactory mcp = new McpFactory(endpoint, token, options.allowInsecureHttp());
        BrokerClient broker = null;
        McpShellToolSession tools = null;
        boolean success = false;
        try {
            broker = new BrokerClient(endpoint, token, options.allowInsecureHttp());
            BrokerInfo info = broker.probe().toCompletableFuture().get();
            if (!info.chat() || !info.streaming() || !info.provider().streamingEnabled()) {
                throw new IllegalStateException("EDT broker does not support streaming chat");
            }
            tools = McpShellToolSession.connect(mcp).toCompletableFuture().get();
            BrokerClient ownedBroker = broker;
            ShellEnvironment environment = new ShellEnvironment("connected",
                    first(info.provider().name(), info.provider().id(), "edt-provider"),
                    first(info.provider().model(), "edt-selected"), endpoint.toASCIIString(),
                    endpoint.resolve("/llm/v1").toASCIIString(),
                    candidate.instanceId(), new BrokeredAgentModel(broker), tools, () -> {
                        try { ownedBroker.close(); }
                        finally { mcp.close(); }
                    });
            success = true;
            return environment;
        } finally {
            if (token != null) Arrays.fill(token, '\0');
            if (!success) {
                if (tools != null) tools.close();
                if (broker != null) broker.close();
                mcp.close();
            }
        }
    }

    private ShellEnvironment standalone(ShellOptions options, List<Candidate> candidates,
            ShellSecretRedactor redactor) throws Exception {
        StandaloneValues values = standaloneValues();
        if (values == null) throw new IllegalArgumentException("standalone provider is incomplete");
        if (candidates.isEmpty()) throw new IllegalArgumentException("MCP endpoint is unavailable");
        Candidate candidate = candidates.get(0);
        URI mcpEndpoint = URI.create(candidate.endpoint());
        URI providerUri = validatedProviderEndpoint(values.endpoint());
        char[] apiKey = readProviderApiKey();
        char[] mcpToken = readMcpToken();
        if (apiKey != null) redactor.add(apiKey);
        if (mcpToken != null) redactor.add(mcpToken);
        McpFactory mcp = new McpFactory(mcpEndpoint, mcpToken, options.allowInsecureHttp());
        McpShellToolSession tools = null;
        ProviderConfiguration configuration = null;
        OpenAiCompatibleProvider providerClient = null;
        boolean success = false;
        try {
            configuration = ProviderConfiguration.builder()
                    .id("cli-openai-compatible")
                    .displayName("CLI OpenAI-compatible provider")
                    .baseUri(providerUri)
                    .defaultModel(values.model())
                    .connectTimeout(Duration.ofSeconds(Math.min(options.turnTimeoutSeconds(), 30)))
                    .requestTimeout(Duration.ofSeconds(options.turnTimeoutSeconds()))
                    .apiKey(apiKey)
                    .build();
            providerClient = new RuntimeProviderFactory().create(configuration);
            tools = McpShellToolSession.connect(mcp).toCompletableFuture().get();
            OpenAiCompatibleProvider ownedProvider = providerClient;
            ShellEnvironment environment = new ShellEnvironment("standalone",
                    values.provider(), values.model(), mcpEndpoint.toASCIIString(),
                    providerUri.toASCIIString(),
                    candidate.instanceId(), new OpenAiCompatibleAgentModel(providerClient), tools, () -> {
                        try { ownedProvider.close(); }
                        finally { mcp.close(); }
                    });
            success = true;
            return environment;
        } finally {
            if (apiKey != null) Arrays.fill(apiKey, '\0');
            if (mcpToken != null) Arrays.fill(mcpToken, '\0');
            if (!success) {
                if (tools != null) tools.close();
                if (providerClient != null) providerClient.close();
                else if (configuration != null) configuration.close();
                mcp.close();
            }
        }
    }

    private StandaloneValues standaloneValues() {
        String selectedProvider = first(provider, "openai-compatible");
        if (!"openai-compatible".equalsIgnoreCase(selectedProvider)
                && !"openai".equalsIgnoreCase(selectedProvider)) return null;
        String endpoint = first(providerEndpoint,
                root.services().host().systemProperty("codepilot.provider.endpoint"),
                root.services().host().environment("CODEPILOT_PROVIDER_ENDPOINT"));
        String selectedModel = first(model,
                root.services().host().systemProperty("codepilot.provider.model"),
                root.services().host().environment("CODEPILOT_PROVIDER_MODEL"));
        if (endpoint == null || selectedModel == null) return null;
        try {
            validatedProviderEndpoint(endpoint);
            return new StandaloneValues("openai-compatible", endpoint, selectedModel);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private URI validatedProviderEndpoint(String value) {
        URI endpoint = URI.create(value);
        if (!endpoint.isAbsolute() || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !("http".equalsIgnoreCase(endpoint.getScheme())
                        || "https".equalsIgnoreCase(endpoint.getScheme()))) {
            throw new IllegalArgumentException("invalid provider endpoint");
        }
        if ("http".equalsIgnoreCase(endpoint.getScheme()) && !loopback(endpoint.getHost())
                && !providerAllowInsecureHttp) {
            throw new IllegalArgumentException("insecure provider endpoint");
        }
        return endpoint;
    }

    private char[] readMcpToken() {
        if (mcpBearerTokenFile != null) return readSecret(mcpBearerTokenFile, "MCP token");
        String value = first(root.services().host().systemProperty("codepilot.mcp.bearerToken"),
                root.services().host().environment("CODEPILOT_MCP_BEARER_TOKEN"));
        return value == null ? null : value.toCharArray();
    }

    private char[] readProviderApiKey() {
        if (providerApiKeyFile != null) return readSecret(providerApiKeyFile, "provider key");
        String value = first(root.services().host().systemProperty("codepilot.provider.apiKey"),
                root.services().host().environment("CODEPILOT_PROVIDER_API_KEY"));
        return value == null ? null : value.toCharArray();
    }

    private char[] readSecret(String path, String label) {
        try { return PrivateUtf8SecretReader.read(Path.of(path), MAX_SECRET_FILE_BYTES); }
        catch (RuntimeException failure) { throw new IllegalArgumentException(label + " file is unreadable"); }
    }

    private String readSystemPrompt() {
        if (systemPromptFile == null) return "";
        try {
            Path path = Path.of(systemPromptFile);
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_TEXT_FILE_BYTES) {
                throw new IllegalArgumentException("system prompt file is unreadable");
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalArgumentException("system prompt file is unreadable");
        }
    }

    private void validateBounds() {
        if (maxSteps <= 0 || maxSteps > 1000) throw new IllegalArgumentException("invalid max steps");
        if (turnTimeoutSeconds <= 0 || turnTimeoutSeconds > 86_400) {
            throw new IllegalArgumentException("invalid turn timeout");
        }
    }

    private static URI normalizedMcp(URI source) {
        if (source == null || source.getHost() == null || source.getScheme() == null
                || source.getUserInfo() != null || source.getQuery() != null || source.getFragment() != null) {
            throw new IllegalArgumentException("invalid MCP endpoint");
        }
        String path = source.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) path = "/mcp";
        else if ("/mcp/".equals(path)) path = "/mcp";
        else if (!"/mcp".equals(path)) throw new IllegalArgumentException("endpoint must target MCP");
        try { return new URI(source.getScheme(), null, source.getHost(), source.getPort(), path, null, null); }
        catch (URISyntaxException failure) { throw new IllegalArgumentException("invalid MCP endpoint"); }
    }

    private static boolean loopback(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if ("localhost".equals(value) || "::1".equals(value)) return true;
        String[] octets = value.split("\\.", -1);
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

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return null;
    }

    private ShellOptions options() {
        return new ShellOptions(mode, instanceId, mcpEndpoint, mcpBearerTokenFile,
                allowInsecureHttp, provider, providerEndpoint, model, providerApiKeyFile,
                providerAllowInsecureHttp, maxSteps, turnTimeoutSeconds, systemPromptFile);
    }

    private record StandaloneValues(String provider, String endpoint, String model) { }

    private static final class McpFactory implements McpShellToolSession.ClientFactory, AutoCloseable {
        private final URI endpoint;
        private final boolean allowInsecure;
        private char[] token;
        private boolean closed;
        McpFactory(URI endpoint, char[] token, boolean allowInsecure) {
            this.endpoint = endpoint;
            this.allowInsecure = allowInsecure;
            this.token = token == null ? null : token.clone();
        }
        @Override public synchronized McpShellToolSession.ClientResource create() {
            if (closed) throw new IllegalStateException("MCP factory is closed");
            McpClientConfig config = McpClientConfig.builder(endpoint)
                    .connectTimeout(Duration.ofSeconds(10)).requestTimeout(Duration.ofSeconds(60))
                    .allowInsecureHttp(allowInsecure).bearerToken(token).build();
            return new McpShellToolSession.ClientResource(new McpClient(config), config);
        }
        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            if (token != null) Arrays.fill(token, '\0');
            token = null;
        }
    }
}
