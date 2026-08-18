/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;

import com.codepilot1c.cli.ExitCodes;
import com.codepilot1c.cli.supervisor.DefaultSupervisorFileSystem;
import com.codepilot1c.cli.supervisor.InstanceRecord;
import com.codepilot1c.cli.supervisor.InstanceRegistry;
import com.codepilot1c.runtime.mcp.InitializeResult;
import com.codepilot1c.runtime.mcp.McpClient;
import com.codepilot1c.runtime.mcp.McpClientConfig;
import com.codepilot1c.runtime.mcp.McpClientException;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Shared endpoint resolution, lifecycle, JSON conversion, and safe error handling for MCP commands. */
final class McpCommandSupport {
    private static final long MAX_BEARER_FILE_BYTES = 64 * 1024;
    private static final long MAX_ARGUMENT_BYTES = 1024 * 1024;
    private static final Pattern KEY_SEPARATORS = Pattern.compile("[\\s._-]");
    /** Audited canonical names for values that are credentials rather than ordinary tool data. */
    private static final Set<String> SENSITIVE_CREDENTIAL_KEYS = Set.of(
            "authorization", "credential", "credentials", "bearer", "bearertoken",
            "consumersecret", "clientsecret", "apisecret", "secretkey", "privatekey", "apikey",
            "password", "passphrase", "token", "authtoken", "accesstoken", "refreshtoken", "idtoken");
    /** Complete header value only; prose mentioning Bearer must remain observable tool output. */
    private static final Pattern BEARER_AUTHORIZATION_VALUE = Pattern.compile(
            "(?i)^\\s*(?:authorization\\s*:\\s*)?bearer\\s+[A-Za-z0-9._~+/=-]{8,}\\s*$");

    private McpCommandSupport() { }

    static int health(RootCommand root, McpCommand options) {
        String command = "mcp health";
        try (Connection connection = connect(root, options)) {
            var result = await(connection.client().healthReady());
            Map<String, Object> output = base(command, connection.endpoint());
            boolean authenticationFailed = result.statusCode() == 401 || result.statusCode() == 403;
            output.put("status", authenticationFailed ? "failed" : result.ready() ? "ready" : "not_ready");
            if (authenticationFailed) output.put("error", "authentication_failed");
            output.put("ready", result.ready());
            output.put("httpStatus", result.statusCode());
            if (result.body() != null) output.put("body", jsonValue(result.body()));
            CommandOutput.print(root, authenticationFailed ? "error[authentication_failed]"
                    : result.ready() ? "ready" : "not ready", output);
            if (authenticationFailed) return ExitCodes.AUTH;
            return result.ready() ? ExitCodes.OK : ExitCodes.EDT_UNAVAILABLE;
        } catch (McpUsageException exception) {
            return usage(root, command, exception.code());
        } catch (RuntimeException exception) {
            return failure(root, command, endpointForError(root, options), exception);
        }
    }

    static int initialize(RootCommand root, McpCommand options) {
        return session(root, options, "mcp initialize", (client, initialized, endpoint) -> {
            Map<String, Object> output = initialized(command("mcp initialize", endpoint), initialized);
            output.put("status", "initialized");
            CommandOutput.print(root, "initialized: " + initialized.protocolVersion(), output);
            return ExitCodes.OK;
        });
    }

    static int tools(RootCommand root, McpCommand options) {
        return session(root, options, "mcp tools", (client, initialized, endpoint) -> {
            var result = await(client.listTools());
            Map<String, Object> output = initialized(command("mcp tools", endpoint), initialized);
            output.put("status", "ok");
            output.put("count", result.tools().size());
            List<Map<String, Object>> tools = new ArrayList<>();
            result.tools().forEach(tool -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("name", tool.name());
                if (tool.description() != null) value.put("description", safeText(tool.description()));
                if (tool.inputSchema() != null) value.put("inputSchema", jsonValue(tool.inputSchema()));
                tools.add(value);
            });
            output.put("tools", tools);
            CommandOutput.print(root, result.tools().size() + " tool(s)", output);
            return ExitCodes.OK;
        });
    }

    static int call(RootCommand root, McpCommand options, String name, JsonObject arguments) {
        return session(root, options, "mcp call", (client, initialized, endpoint) -> {
            var result = await(client.callTool(name, arguments));
            Map<String, Object> output = initialized(command("mcp call", endpoint), initialized);
            output.put("status", result.isError() ? "tool_error" : "ok");
            output.put("tool", name);
            output.put("isError", result.isError());
            output.put("result", jsonValue(result.rawResult()));
            CommandOutput.print(root, result.isError() ? "tool returned an error" : "ok", output);
            return result.isError() ? ExitCodes.FAILURE : ExitCodes.OK;
        });
    }

    static int ping(RootCommand root, McpCommand options) {
        return session(root, options, "mcp ping", (client, initialized, endpoint) -> {
            await(client.ping());
            Map<String, Object> output = initialized(command("mcp ping", endpoint), initialized);
            output.put("status", "ok");
            CommandOutput.print(root, "pong", output);
            return ExitCodes.OK;
        });
    }

    static int close(RootCommand root, McpCommand options) {
        return session(root, options, "mcp close", (client, initialized, endpoint) -> {
            await(client.closeAsync());
            Map<String, Object> output = initialized(command("mcp close", endpoint), initialized);
            output.put("status", "closed");
            CommandOutput.print(root, "closed", output);
            return ExitCodes.OK;
        });
    }

    private static int session(RootCommand root, McpCommand options, String command, SessionAction action) {
        try (Connection connection = connect(root, options)) {
            InitializeResult initialized = await(connection.client().initialize());
            return action.run(connection.client(), initialized, connection.endpoint());
        } catch (McpUsageException exception) {
            return usage(root, command, exception.code());
        } catch (RuntimeException exception) {
            return failure(root, command, endpointForError(root, options), exception);
        }
    }

    static JsonObject parseArguments(String value) {
        try {
            JsonElement parsed = com.google.gson.JsonParser.parseString(value);
            if (!parsed.isJsonObject()) throw new McpUsageException("arguments_must_be_object");
            return parsed.getAsJsonObject();
        } catch (com.google.gson.JsonParseException exception) {
            throw new McpUsageException("arguments_invalid_json");
        }
    }

    static String readUtf8(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_ARGUMENT_BYTES) {
                throw new McpUsageException("arguments_file_unreadable");
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException | SecurityException exception) {
            throw new McpUsageException("arguments_file_unreadable");
        }
    }

    static String readStandardInput(RootCommand root) {
        try {
            char[] chunk = new char[4096];
            StringBuilder value = new StringBuilder();
            int count;
            while ((count = root.services().in().read(chunk)) != -1) {
                if (value.length() + count > MAX_ARGUMENT_BYTES) throw new McpUsageException("arguments_too_large");
                value.append(chunk, 0, count);
            }
            return value.toString();
        } catch (IOException exception) {
            throw new McpUsageException("arguments_stdin_unreadable");
        }
    }

    static Connection connect(RootCommand root, McpConnectionOptions options) {
        URI endpoint = resolveEndpoint(root, options);
        char[] token = readBearerToken(root, options);
        try {
            McpClientConfig config = McpClientConfig.builder(endpoint)
                    .connectTimeout(Duration.ofSeconds(10))
                    .requestTimeout(Duration.ofSeconds(60))
                    .allowInsecureHttp(options.allowInsecureHttp())
                    .bearerToken(token)
                    .build();
            return new Connection(endpoint, new McpClient(config));
        } catch (IllegalArgumentException exception) {
            throw new McpUsageException("invalid_endpoint");
        } finally {
            if (token != null) java.util.Arrays.fill(token, '\0');
        }
    }

    static URI resolveEndpoint(RootCommand root, McpConnectionOptions options) {
        try {
            if (options.endpoint() != null && options.instanceId() != null) {
                throw new McpUsageException("endpoint_selection_conflict");
            }
            URI value;
            if (options.instanceId() != null) value = selectedInstance(root, options.instanceId());
            else if (options.endpoint() != null) value = URI.create(options.endpoint());
            else value = root.services().configuration().endpoint();
            return mcpEndpoint(value);
        } catch (McpUsageException exception) {
            throw exception;
        } catch (RuntimeException | java.net.URISyntaxException exception) {
            throw new McpUsageException("invalid_endpoint");
        }
    }

    private static URI selectedInstance(RootCommand root, String instanceId) {
        try {
            Path directory = Path.of(root.services().host().userHome(), ".codepilot1c", "instances");
            InstanceRegistry registry = new InstanceRegistry(new DefaultSupervisorFileSystem(), directory);
            InstanceRecord instance = registry.find(instanceId)
                    .orElseThrow(() -> new McpUsageException("instance_not_found"));
            return URI.create(instance.baseUrl());
        } catch (McpUsageException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException | SecurityException exception) {
            throw new McpUsageException("instance_record_invalid");
        }
    }

    private static URI mcpEndpoint(URI source) {
        if (source == null || source.getUserInfo() != null || source.getQuery() != null || source.getFragment() != null
                || source.getHost() == null || source.getScheme() == null) throw new McpUsageException("invalid_endpoint");
        String path = source.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) path = "/mcp";
        else if ("/mcp/".equals(path)) path = "/mcp";
        else if (!"/mcp".equals(path)) throw new McpUsageException("endpoint_must_target_mcp");
        try {
            return new URI(source.getScheme(), null, source.getHost(), source.getPort(), path, null, null);
        } catch (java.net.URISyntaxException exception) {
            throw new McpUsageException("invalid_endpoint");
        }
    }

    private static char[] readBearerToken(RootCommand root, McpConnectionOptions options) {
        String token;
        if (options.bearerTokenFile() != null) {
            try {
                Path path = Path.of(options.bearerTokenFile());
                token = readPrivateUtf8Secret(path, MAX_BEARER_FILE_BYTES,
                        "bearer_token_file_unreadable");
            } catch (McpUsageException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new McpUsageException("bearer_token_file_unreadable");
            }
        } else {
            token = first(root.services().host().systemProperty("codepilot.mcp.bearerToken"),
                    root.services().host().environment("CODEPILOT_MCP_BEARER_TOKEN"));
        }
        if (token == null || token.isBlank()) return null;
        return token.toCharArray();
    }

    static String readPrivateUtf8Secret(Path path, long maximumBytes, String errorCode) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(path) > maximumBytes || isBroadlyReadable(path)) {
                throw new McpUsageException(errorCode);
            }
            String value = Files.readString(path, StandardCharsets.UTF_8).trim();
            if (value.isEmpty()) throw new McpUsageException(errorCode);
            return value;
        } catch (McpUsageException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new McpUsageException(errorCode);
        }
    }

    private static boolean isBroadlyReadable(Path path) throws IOException {
        try {
            var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            return permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE);
        } catch (UnsupportedOperationException ignored) {
            return false;
        }
    }

    private static String first(String property, String environment) {
        if (property != null && !property.isBlank()) return property.trim();
        return environment == null || environment.isBlank() ? null : environment.trim();
    }

    private static <T> T await(java.util.concurrent.CompletableFuture<T> future) {
        try { return future.join(); }
        catch (CompletionException exception) { throw unwrap(exception); }
    }

    private static RuntimeException unwrap(CompletionException exception) {
        Throwable value = exception;
        while ((value instanceof CompletionException || value instanceof java.util.concurrent.ExecutionException)
                && value.getCause() != null) value = value.getCause();
        return value instanceof RuntimeException runtime ? runtime
                : new RuntimeException("MCP request failed", value);
    }

    static int usage(RootCommand root, String command, String code) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("command", command);
        output.put("status", "failed");
        output.put("error", code);
        CommandOutput.print(root, "error[usage]: " + code, output);
        return ExitCodes.USAGE;
    }

    private static int failure(RootCommand root, String command, String endpoint, RuntimeException exception) {
        McpClientException mcp = exception instanceof McpClientException value ? value : null;
        int exit = mcp != null && (mcp.httpStatus() == 401 || mcp.httpStatus() == 403)
                ? ExitCodes.AUTH : unavailable(mcp) ? ExitCodes.EDT_UNAVAILABLE : ExitCodes.FAILURE;
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("command", command);
        if (endpoint != null) output.put("endpoint", endpoint);
        output.put("status", "failed");
        output.put("error", exit == ExitCodes.AUTH ? "authentication_failed"
                : exit == ExitCodes.EDT_UNAVAILABLE ? "edt_unavailable" : "mcp_failed");
        if (mcp != null) {
            output.put("failureKind", mcp.kind().name().toLowerCase(Locale.ROOT));
            if (mcp.httpStatus() >= 0) output.put("httpStatus", mcp.httpStatus());
            if (mcp.hasRpcError()) output.put("rpcCode", mcp.rpcCode());
        }
        String text = exit == ExitCodes.AUTH ? "error[authentication_failed]"
                : exit == ExitCodes.EDT_UNAVAILABLE ? "error[edt_unavailable]" : "error[mcp_failed]";
        CommandOutput.print(root, text, output);
        return exit;
    }

    private static boolean unavailable(McpClientException value) {
        if (value == null) return false;
        return switch (value.kind()) {
        case TRANSPORT, HTTP, PROTOCOL, MALFORMED_RESPONSE -> true;
        default -> false;
        };
    }

    private static String endpointForError(RootCommand root, McpConnectionOptions options) {
        try { return resolveEndpoint(root, options).toASCIIString(); }
        catch (RuntimeException ignored) { return null; }
    }

    private static Map<String, Object> command(String command, URI endpoint) {
        return base(command, endpoint);
    }

    private static Map<String, Object> initialized(Map<String, Object> output, InitializeResult initialized) {
        output.put("protocolVersion", initialized.protocolVersion());
        if (initialized.serverInfo() != null) output.put("serverInfo", jsonValue(initialized.serverInfo()));
        if (initialized.capabilities() != null) output.put("capabilities", jsonValue(initialized.capabilities()));
        if (initialized.experimentalCodepilot() != null) {
            output.put("experimentalCodepilot", jsonValue(initialized.experimentalCodepilot()));
        }
        return output;
    }

    private static Map<String, Object> base(String command, URI endpoint) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command", command);
        result.put("endpoint", endpoint.toASCIIString());
        return result;
    }

    static Object jsonValue(JsonElement value) {
        if (value == null || value instanceof JsonNull) return null;
        if (value.isJsonObject()) {
            Map<String, Object> output = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                if (!isSensitiveCredentialKey(entry.getKey())) output.put(entry.getKey(), jsonValue(entry.getValue()));
            }
            return output;
        }
        if (value.isJsonArray()) {
            List<Object> output = new ArrayList<>();
            for (JsonElement item : value.getAsJsonArray()) output.add(jsonValue(item));
            return output;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) return primitive.getAsBoolean();
        if (primitive.isNumber()) return primitive.getAsNumber();
        return safeText(primitive.getAsString());
    }

    static boolean isSensitiveCredentialKey(String key) {
        if (key == null || key.isBlank()) return false;
        String normalized = KEY_SEPARATORS.matcher(key.toLowerCase(Locale.ROOT)).replaceAll("");
        return SENSITIVE_CREDENTIAL_KEYS.contains(normalized);
    }

    static String safeText(String value) {
        if (value == null) return null;
        return BEARER_AUTHORIZATION_VALUE.matcher(value).matches() ? "<redacted>" : value;
    }

    record Connection(URI endpoint, McpClient client) implements AutoCloseable {
        @Override public void close() { client.close(); }
    }

    @FunctionalInterface
    private interface SessionAction {
        int run(McpClient client, InitializeResult initialized, URI endpoint);
    }

    static final class McpUsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;
        McpUsageException(String code) { this.code = code; }
        String code() { return code; }
    }
}
