/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell.session;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import com.codepilot1c.runtime.agent.AgentMessage;
import com.codepilot1c.runtime.agent.ToolCall;
import com.codepilot1c.runtime.agent.ToolExecutionResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Secure append-only persistence for provider-neutral CLI agent transcripts.
 *
 * <p>All public lifecycle methods are synchronized. Consequently one store
 * instance serializes append and metadata replacement and recalculates counts
 * from valid JSONL records before each update. If a process fails after the
 * append but before metadata replacement, the next list/resume/append exposes
 * the valid record and its reconciled counts.</p>
 */
public final class SessionStore {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_TITLE_CODE_POINTS = 60;
    public static final int MAX_METADATA_BYTES = 1024 * 1024;
    public static final int MAX_MESSAGE_BYTES = 1024 * 1024;
    public static final int MAX_TRANSCRIPT_BYTES = 64 * 1024 * 1024;

    private static final String META_SUFFIX = ".meta.json"; //$NON-NLS-1$
    private static final String TRANSCRIPT_SUFFIX = ".jsonl"; //$NON-NLS-1$

    private final PrivateFileWriter files;
    private final Function<String, String> redactor;
    private final Consumer<String> warningSink;
    private final Clock clock;

    public SessionStore(Path root, Function<String, String> redactor) {
        this(new PrivateFileWriter(root), redactor, System.err::println, Clock.systemUTC());
    }

    public SessionStore(Path root, Function<String, String> redactor, Consumer<String> warningSink) {
        this(new PrivateFileWriter(root), redactor, warningSink, Clock.systemUTC());
    }

    public SessionStore(
            PrivateFileWriter files,
            Function<String, String> redactor,
            Consumer<String> warningSink,
            Clock clock) {
        this.files = Objects.requireNonNull(files, "files"); //$NON-NLS-1$
        this.redactor = Objects.requireNonNull(redactor, "redactor"); //$NON-NLS-1$
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink"); //$NON-NLS-1$
        this.clock = Objects.requireNonNull(clock, "clock"); //$NON-NLS-1$
    }

    /** Cross-platform default based on the JVM's platform-aware user home. */
    public static Path defaultRoot() {
        String home = System.getProperty("user.home"); //$NON-NLS-1$
        if (home == null || home.isBlank()) throw new IllegalStateException("user.home is not configured"); //$NON-NLS-1$
        return defaultRoot(Path.of(home));
    }

    public static Path defaultRoot(Path userHome) {
        Objects.requireNonNull(userHome, "userHome"); //$NON-NLS-1$
        return userHome.resolve(".codepilot1c").resolve("sessions").toAbsolutePath().normalize(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public Path root() {
        return files.root();
    }

    public synchronized SessionMetadata create(
            String mode,
            String provider,
            String model,
            String endpoint,
            String instanceId) throws IOException {
        return create(SessionContext.fromEndpoint(mode, provider, model, endpoint, instanceId));
    }

    public synchronized SessionMetadata create(SessionContext context) throws IOException {
        Objects.requireNonNull(context, "context"); //$NON-NLS-1$
        files.ensureDirectory();
        SessionContext safeContext = redact(context);
        UUID id;
        do {
            id = UUID.randomUUID();
        } while (files.exists(metaName(id)) || files.exists(transcriptName(id)));

        Instant now = clock.instant();
        SessionMetadata metadata = new SessionMetadata(SCHEMA_VERSION, id, "", now, now, //$NON-NLS-1$
                safeContext.mode(), safeContext.provider(), safeContext.model(),
                safeContext.endpointFingerprint(), 0, 0);
        files.writeAtomically(metaName(id), encodeMetadata(metadata));
        return metadata;
    }

    /** Appends one redacted message and then atomically replaces its summary. */
    public synchronized SessionMetadata append(UUID id, AgentMessage message) throws IOException {
        Objects.requireNonNull(message, "message"); //$NON-NLS-1$
        StoredState state = readState(requireId(id));
        Instant recordedAt = clock.instant();
        JsonObject encoded = encodeMessage(message, recordedAt);
        String line = encoded.toString();
        if (line.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
            throw new IOException("session message exceeds persistence limit"); //$NON-NLS-1$
        }

        // Append first: metadata can be reconciled from the record after a crash,
        // whereas updating metadata first could permanently overstate a lost append.
        files.appendLine(transcriptName(id), line, MAX_TRANSCRIPT_BYTES);
        List<StoredMessage> messages = new ArrayList<>(state.storedMessages());
        messages.add(new StoredMessage(decodeMessage(encoded), recordedAt));
        SessionMetadata updated = reconcile(state.metadata(), messages);
        files.writeAtomically(metaName(id), encodeMetadata(updated));
        return updated;
    }

    public synchronized SessionMetadata append(String id, AgentMessage message) throws IOException {
        return append(parseId(id), message);
    }

    public synchronized SessionMetadata metadata(UUID id) throws IOException {
        return readState(requireId(id)).metadata();
    }

    public synchronized SessionMetadata metadata(String id) throws IOException {
        return metadata(parseId(id));
    }

    /** Lists valid session summaries by newest update first, with UUID as a stable tie-breaker. */
    public synchronized List<SessionMetadata> list() throws IOException {
        List<SessionMetadata> result = new ArrayList<>();
        for (String fileName : files.listFileNames(META_SUFFIX)) {
            Optional<UUID> id = idFromMetaName(fileName);
            if (id.isEmpty()) continue;
            try {
                result.add(readState(id.get()).metadata());
            } catch (UnsupportedSessionSchemaException unsupported) {
                throw unsupported;
            } catch (IOException | IllegalArgumentException corrupt) {
                warn("Skipped corrupt session metadata in " + fileName); //$NON-NLS-1$
            }
        }
        result.sort(Comparator.comparing(SessionMetadata::updatedAt).reversed()
                .thenComparing(metadata -> metadata.id().toString()));
        return List.copyOf(result);
    }

    public synchronized ResumedSession resume(UUID id, SessionContext current) throws IOException {
        Objects.requireNonNull(current, "current"); //$NON-NLS-1$
        StoredState state = readState(requireId(id));
        SessionContext safeCurrent = redact(current);
        return new ResumedSession(state.metadata(), state.messages(),
                SessionMismatch.compare(state.metadata().context(), safeCurrent));
    }

    public synchronized ResumedSession resume(UUID id) throws IOException {
        StoredState state = readState(requireId(id));
        SessionContext persisted = state.metadata().context();
        return new ResumedSession(state.metadata(), state.messages(),
                SessionMismatch.compare(persisted, persisted));
    }

    public synchronized ResumedSession resume(String id, SessionContext current) throws IOException {
        return resume(parseId(id), current);
    }

    public synchronized ResumedSession resume(String id) throws IOException {
        return resume(parseId(id));
    }

    /** SHA-256 of a normalized endpoint plus normalized instance id; raw inputs are never returned. */
    public static String endpointFingerprint(String endpoint, String instanceId) {
        String normalizedEndpoint = normalizeEndpoint(endpoint);
        String normalizedInstance = normalizeInstanceId(instanceId);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
            byte[] hash = digest.digest((normalizedEndpoint + '\n' + normalizedInstance)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(hash.length * 2);
            for (byte item : hash) value.append(String.format(Locale.ROOT, "%02x", item & 0xff)); //$NON-NLS-1$
            return value.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible); //$NON-NLS-1$
        }
    }

    private StoredState readState(UUID id) throws IOException {
        SessionMetadata stored = readMetadata(id);
        List<StoredMessage> messages = readMessages(id);
        return new StoredState(reconcile(stored, messages), messages);
    }

    private SessionMetadata readMetadata(UUID id) throws IOException {
        String name = metaName(id);
        if (!files.exists(name)) throw new NoSuchFileException(name);
        JsonObject object = parseObject(files.readString(name, MAX_METADATA_BYTES), "session metadata"); //$NON-NLS-1$
        int schema = integer(object, "schemaVersion"); //$NON-NLS-1$
        if (schema != SCHEMA_VERSION) throw new UnsupportedSessionSchemaException(schema);
        try {
            UUID storedId = UUID.fromString(string(object, "id")); //$NON-NLS-1$
            if (!storedId.equals(id)) throw new IOException("session metadata id mismatch"); //$NON-NLS-1$
            return new SessionMetadata(schema, storedId,
                    string(object, "title"), //$NON-NLS-1$
                    Instant.parse(string(object, "createdAt")), //$NON-NLS-1$
                    Instant.parse(string(object, "updatedAt")), //$NON-NLS-1$
                    string(object, "mode"), //$NON-NLS-1$
                    string(object, "provider"), //$NON-NLS-1$
                    string(object, "model"), //$NON-NLS-1$
                    string(object, "endpointFingerprint"), //$NON-NLS-1$
                    longInteger(object, "turns"), //$NON-NLS-1$
                    longInteger(object, "messageCount")); //$NON-NLS-1$
        } catch (DateTimeParseException | IllegalArgumentException failure) {
            throw new IOException("invalid session metadata", failure); //$NON-NLS-1$
        }
    }

    private List<StoredMessage> readMessages(UUID id) throws IOException {
        String name = transcriptName(id);
        if (!files.exists(name)) return List.of();
        String content = files.readString(name, MAX_TRANSCRIPT_BYTES);
        String[] lines = content.split("\\n", -1); //$NON-NLS-1$
        List<StoredMessage> messages = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1); //$NON-NLS-1$
            if (line.isEmpty() && index == lines.length - 1) continue;
            try {
                JsonObject object = parseObject(line, "session message"); //$NON-NLS-1$
                int schema = integer(object, "schemaVersion"); //$NON-NLS-1$
                if (schema != SCHEMA_VERSION) throw new UnsupportedSessionSchemaException(schema);
                Instant recordedAt = Instant.parse(string(object, "recordedAt")); //$NON-NLS-1$
                messages.add(new StoredMessage(decodeMessage(object), recordedAt));
            } catch (IOException | DateTimeParseException | IllegalArgumentException corrupt) {
                warn("Skipped corrupt session message at line " + (index + 1) + " in " + name); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return List.copyOf(messages);
    }

    private SessionMetadata reconcile(SessionMetadata metadata, List<StoredMessage> messages) {
        long turns = 0;
        String firstUserText = null;
        Instant updatedAt = metadata.updatedAt();
        for (StoredMessage stored : messages) {
            if (stored.recordedAt().isAfter(updatedAt)) updatedAt = stored.recordedAt();
            if (stored.message() instanceof AgentMessage.Text text && text.role() == AgentMessage.Role.USER) {
                turns++;
                if (firstUserText == null) firstUserText = text.content();
            }
        }
        String title = firstUserText == null ? metadata.title() : title(firstUserText);
        return new SessionMetadata(SCHEMA_VERSION, metadata.id(), title, metadata.createdAt(), updatedAt,
                metadata.mode(), metadata.provider(), metadata.model(), metadata.endpointFingerprint(),
                turns, messages.size());
    }

    private String encodeMetadata(SessionMetadata metadata) {
        JsonObject object = new JsonObject();
        object.addProperty("schemaVersion", SCHEMA_VERSION); //$NON-NLS-1$
        object.addProperty("id", metadata.id().toString()); //$NON-NLS-1$
        object.addProperty("title", redact(metadata.title())); //$NON-NLS-1$
        object.addProperty("createdAt", metadata.createdAt().toString()); //$NON-NLS-1$
        object.addProperty("updatedAt", metadata.updatedAt().toString()); //$NON-NLS-1$
        object.addProperty("mode", redact(metadata.mode())); //$NON-NLS-1$
        object.addProperty("provider", redact(metadata.provider())); //$NON-NLS-1$
        object.addProperty("model", redact(metadata.model())); //$NON-NLS-1$
        object.addProperty("endpointFingerprint", metadata.endpointFingerprint()); //$NON-NLS-1$
        object.addProperty("turns", metadata.turns()); //$NON-NLS-1$
        object.addProperty("messageCount", metadata.messageCount()); //$NON-NLS-1$
        return object + System.lineSeparator();
    }

    private JsonObject encodeMessage(AgentMessage message, Instant recordedAt) {
        JsonObject object = new JsonObject();
        object.addProperty("schemaVersion", SCHEMA_VERSION); //$NON-NLS-1$
        object.addProperty("recordedAt", recordedAt.toString()); //$NON-NLS-1$
        if (message instanceof AgentMessage.Text text) {
            object.addProperty("type", "text"); //$NON-NLS-1$ //$NON-NLS-2$
            object.addProperty("role", text.role().name().toLowerCase(Locale.ROOT)); //$NON-NLS-1$
            object.addProperty("content", redact(text.content())); //$NON-NLS-1$
        } else if (message instanceof AgentMessage.Assistant assistant) {
            object.addProperty("type", "assistant"); //$NON-NLS-1$ //$NON-NLS-2$
            assistant.text().ifPresent(value -> object.addProperty("text", redact(value))); //$NON-NLS-1$
            JsonArray calls = new JsonArray();
            for (ToolCall call : assistant.toolCalls()) {
                JsonObject encodedCall = new JsonObject();
                encodedCall.addProperty("id", redact(call.id())); //$NON-NLS-1$
                encodedCall.addProperty("name", redact(call.name())); //$NON-NLS-1$
                encodedCall.addProperty("argumentsJson", redact(call.argumentsJson())); //$NON-NLS-1$
                calls.add(encodedCall);
            }
            object.add("toolCalls", calls); //$NON-NLS-1$
        } else if (message instanceof AgentMessage.Tool tool) {
            object.addProperty("type", "tool"); //$NON-NLS-1$ //$NON-NLS-2$
            object.addProperty("callId", redact(tool.callId())); //$NON-NLS-1$
            object.addProperty("toolName", redact(tool.toolName())); //$NON-NLS-1$
            JsonObject result = new JsonObject();
            result.addProperty("error", tool.result().error()); //$NON-NLS-1$
            result.addProperty("code", redact(tool.result().code())); //$NON-NLS-1$
            result.addProperty("message", redact(tool.result().message())); //$NON-NLS-1$
            result.add("data", redact(tool.result().data())); //$NON-NLS-1$
            object.add("result", result); //$NON-NLS-1$
        } else {
            throw new IllegalArgumentException("unsupported agent message"); //$NON-NLS-1$
        }
        return object;
    }

    private static AgentMessage decodeMessage(JsonObject object) throws IOException {
        String type = string(object, "type"); //$NON-NLS-1$
        try {
            return switch (type) {
                case "text" -> new AgentMessage.Text( //$NON-NLS-1$
                        switch (string(object, "role")) { //$NON-NLS-1$
                            case "system" -> AgentMessage.Role.SYSTEM; //$NON-NLS-1$
                            case "user" -> AgentMessage.Role.USER; //$NON-NLS-1$
                            default -> throw new IllegalArgumentException("invalid text role"); //$NON-NLS-1$
                        }, string(object, "content")); //$NON-NLS-1$
                case "assistant" -> decodeAssistant(object); //$NON-NLS-1$
                case "tool" -> decodeTool(object); //$NON-NLS-1$
                default -> throw new IllegalArgumentException("invalid message type"); //$NON-NLS-1$
            };
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw new IOException("invalid session message", failure); //$NON-NLS-1$
        }
    }

    private static AgentMessage.Assistant decodeAssistant(JsonObject object) throws IOException {
        Optional<String> text = object.has("text") && !object.get("text").isJsonNull() //$NON-NLS-1$ //$NON-NLS-2$
                ? Optional.of(string(object, "text")) : Optional.empty(); //$NON-NLS-1$
        JsonArray array = array(object, "toolCalls"); //$NON-NLS-1$
        List<ToolCall> calls = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) throw new IOException("invalid session tool call"); //$NON-NLS-1$
            JsonObject call = element.getAsJsonObject();
            calls.add(new ToolCall(string(call, "id"), string(call, "name"), //$NON-NLS-1$ //$NON-NLS-2$
                    string(call, "argumentsJson"))); //$NON-NLS-1$
        }
        return new AgentMessage.Assistant(text, calls);
    }

    private static AgentMessage.Tool decodeTool(JsonObject object) throws IOException {
        JsonObject result = object(object, "result"); //$NON-NLS-1$
        return new AgentMessage.Tool(string(object, "callId"), string(object, "toolName"), //$NON-NLS-1$ //$NON-NLS-2$
                new ToolExecutionResult(bool(result, "error"), string(result, "code"), //$NON-NLS-1$ //$NON-NLS-2$
                        string(result, "message"), required(result, "data").deepCopy())); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private JsonElement redact(JsonElement input) {
        if (input == null || input.isJsonNull()) return JsonNull.INSTANCE;
        if (input.isJsonPrimitive()) {
            JsonPrimitive primitive = input.getAsJsonPrimitive();
            if (primitive.isString()) return new JsonPrimitive(redact(primitive.getAsString()));
            return primitive.deepCopy();
        }
        if (input.isJsonArray()) {
            JsonArray output = new JsonArray();
            for (JsonElement item : input.getAsJsonArray()) output.add(redact(item));
            return output;
        }
        JsonObject output = new JsonObject();
        for (var entry : input.getAsJsonObject().entrySet()) {
            output.add(redact(entry.getKey()), redact(entry.getValue()));
        }
        return output;
    }

    private SessionContext redact(SessionContext context) {
        return new SessionContext(redact(context.mode()), redact(context.provider()), redact(context.model()),
                context.endpointFingerprint());
    }

    private String redact(String value) {
        Objects.requireNonNull(value, "persisted string"); //$NON-NLS-1$
        String safe = redactor.apply(value);
        if (safe == null) throw new IllegalArgumentException("redactor returned null"); //$NON-NLS-1$
        return safe;
    }

    private void warn(String warning) {
        try {
            warningSink.accept(redact(warning));
        } catch (RuntimeException ignored) {
            // Diagnostics must never prevent recovery of the remaining transcript.
        }
    }

    private static String title(String text) {
        int count = text.codePointCount(0, text.length());
        if (count <= MAX_TITLE_CODE_POINTS) return text;
        return text.substring(0, text.offsetByCodePoints(0, MAX_TITLE_CODE_POINTS));
    }

    private static String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) throw new IllegalArgumentException("invalid endpoint"); //$NON-NLS-1$
        try {
            URI parsed = new URI(endpoint.trim()).normalize();
            String scheme = parsed.getScheme();
            String host = parsed.getHost();
            if (scheme == null || host == null) throw new IllegalArgumentException("invalid endpoint"); //$NON-NLS-1$
            scheme = scheme.toLowerCase(Locale.ROOT);
            host = host.toLowerCase(Locale.ROOT);
            int port = parsed.getPort();
            if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) port = -1; //$NON-NLS-1$ //$NON-NLS-2$
            String path = parsed.getRawPath();
            if (path == null) path = ""; //$NON-NLS-1$
            while (path.endsWith("/") && !path.isEmpty()) path = path.substring(0, path.length() - 1); //$NON-NLS-1$
            return new URI(scheme, parsed.getRawUserInfo(), host, port, path, parsed.getRawQuery(), null)
                    .toASCIIString();
        } catch (URISyntaxException | IllegalArgumentException failure) {
            throw new IllegalArgumentException("invalid endpoint"); //$NON-NLS-1$
        }
    }

    private static String normalizeInstanceId(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) throw new IllegalArgumentException("invalid instance id"); //$NON-NLS-1$
        String value = instanceId.trim();
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException notUuid) {
            return value.toLowerCase(Locale.ROOT);
        }
    }

    private static JsonObject parseObject(String json, String description) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) throw new IOException("invalid " + description); //$NON-NLS-1$
            return parsed.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException failure) {
            throw new IOException("invalid " + description, failure); //$NON-NLS-1$
        }
    }

    private static JsonElement required(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        if (value == null) throw new IOException("missing session field: " + key); //$NON-NLS-1$
        return value;
    }

    private static String string(JsonObject object, String key) throws IOException {
        JsonElement value = required(object, key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("invalid session string: " + key); //$NON-NLS-1$
        }
        return value.getAsString();
    }

    private static boolean bool(JsonObject object, String key) throws IOException {
        JsonElement value = required(object, key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IOException("invalid session boolean: " + key); //$NON-NLS-1$
        }
        return value.getAsBoolean();
    }

    private static long longInteger(JsonObject object, String key) throws IOException {
        JsonElement value = required(object, key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IOException("invalid session integer: " + key); //$NON-NLS-1$
        }
        try {
            return new BigDecimal(value.getAsString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException failure) {
            throw new IOException("invalid session integer: " + key, failure); //$NON-NLS-1$
        }
    }

    private static int integer(JsonObject object, String key) throws IOException {
        try {
            return Math.toIntExact(longInteger(object, key));
        } catch (ArithmeticException failure) {
            throw new IOException("invalid session integer: " + key, failure); //$NON-NLS-1$
        }
    }

    private static JsonArray array(JsonObject object, String key) throws IOException {
        JsonElement value = required(object, key);
        if (!value.isJsonArray()) throw new IOException("invalid session array: " + key); //$NON-NLS-1$
        return value.getAsJsonArray();
    }

    private static JsonObject object(JsonObject object, String key) throws IOException {
        JsonElement value = required(object, key);
        if (!value.isJsonObject()) throw new IOException("invalid session object: " + key); //$NON-NLS-1$
        return value.getAsJsonObject();
    }

    private static UUID parseId(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value)) throw new IllegalArgumentException();
            return id;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("invalid session id"); //$NON-NLS-1$
        }
    }

    private static UUID requireId(UUID id) {
        return Objects.requireNonNull(id, "id"); //$NON-NLS-1$
    }

    private static Optional<UUID> idFromMetaName(String name) {
        if (!name.endsWith(META_SUFFIX)) return Optional.empty();
        String id = name.substring(0, name.length() - META_SUFFIX.length());
        try {
            UUID parsed = UUID.fromString(id);
            return parsed.toString().equals(id) ? Optional.of(parsed) : Optional.empty();
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private static String metaName(UUID id) {
        return id + META_SUFFIX;
    }

    private static String transcriptName(UUID id) {
        return id + TRANSCRIPT_SUFFIX;
    }

    private record StoredMessage(AgentMessage message, Instant recordedAt) { }

    private record StoredState(SessionMetadata metadata, List<StoredMessage> storedMessages) {
        StoredState {
            storedMessages = List.copyOf(storedMessages);
        }

        List<AgentMessage> messages() {
            return storedMessages.stream().map(StoredMessage::message).toList();
        }
    }
}
