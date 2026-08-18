/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.codepilot1c.runtime.agent.AgentMessage;
import com.codepilot1c.runtime.agent.ToolCall;
import com.codepilot1c.runtime.agent.ToolExecutionResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class SessionStoreTest {
    private static final String INSTANCE = "11111111-2222-3333-4444-555555555555";
    private static final Instant START = Instant.parse("2026-08-18T10:00:00Z");

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void roundTripsEveryProviderNeutralMessageVariantAndCountsTurns() throws Exception {
        Path root = temporary.newFolder("sessions").toPath();
        SessionStore store = store(root, Clock.fixed(START, ZoneOffset.UTC), warning -> { });
        SessionMetadata created = store.create("build", "openai-compatible", "model-a", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "https://localhost:9443/api/", INSTANCE); //$NON-NLS-1$

        JsonObject data = new JsonObject();
        data.addProperty("path", "src/main.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonArray matches = new JsonArray();
        matches.add(3);
        matches.add(true);
        data.add("matches", matches); //$NON-NLS-1$
        List<AgentMessage> expected = List.of(
                new AgentMessage.Text(AgentMessage.Role.SYSTEM, "system context"), //$NON-NLS-1$
                new AgentMessage.Text(AgentMessage.Role.USER, "Please inspect the module"), //$NON-NLS-1$
                new AgentMessage.Assistant(Optional.of("I will inspect it"), List.of( //$NON-NLS-1$
                        new ToolCall("call-1", "read_file", "{\"path\":\"src/main.bsl\"}"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                new AgentMessage.Tool("call-1", "read_file", //$NON-NLS-1$ //$NON-NLS-2$
                        new ToolExecutionResult(false, "OK", "read complete", data)), //$NON-NLS-1$ //$NON-NLS-2$
                AgentMessage.Assistant.text("Finished"), //$NON-NLS-1$
                new AgentMessage.Tool("call-2", "write_file", //$NON-NLS-1$ //$NON-NLS-2$
                        ToolExecutionResult.failure("DENIED", "permission denied"))); //$NON-NLS-1$ //$NON-NLS-2$

        SessionMetadata updated = created;
        for (AgentMessage message : expected) updated = store.append(created.id(), message);

        ResumedSession resumed = store.resume(created.id());
        assertEquals(expected, resumed.messages());
        assertEquals(expected.size(), updated.messageCount());
        assertEquals(1, updated.turns());
        assertEquals("Please inspect the module", updated.title()); //$NON-NLS-1$
        assertFalse(resumed.mismatch().present());

        Path transcript = root.resolve(created.id() + ".jsonl"); //$NON-NLS-1$
        assertEquals(expected.size(), Files.readAllLines(transcript, StandardCharsets.UTF_8).size());
        assertTrue(Files.readString(transcript).contains("\"type\":\"assistant\"")); //$NON-NLS-1$
        assertTrue(Files.readString(transcript).contains("\"type\":\"tool\"")); //$NON-NLS-1$
    }

    @Test public void titleUsesFirstUserTextAndTruncatesAtSixtyUnicodeCodePoints() throws Exception {
        SessionStore store = store(temporary.newFolder("unicode").toPath(), //$NON-NLS-1$
                Clock.fixed(START, ZoneOffset.UTC), warning -> { });
        SessionMetadata metadata = store.create("plan", "p", "m", "http://localhost", INSTANCE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        store.append(metadata.id(), new AgentMessage.Text(AgentMessage.Role.SYSTEM, "not the title")); //$NON-NLS-1$
        String user = "😀".repeat(59) + "Ж" + "tail"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        SessionMetadata updated = store.append(metadata.id(),
                new AgentMessage.Text(AgentMessage.Role.USER, user));
        store.append(metadata.id(), new AgentMessage.Text(AgentMessage.Role.USER, "later")); //$NON-NLS-1$

        assertEquals(60, updated.title().codePointCount(0, updated.title().length()));
        assertEquals("😀".repeat(59) + "Ж", updated.title()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(updated.title(), store.metadata(metadata.id()).title());
    }

    @Test public void skipsCorruptAndTruncatedLinesAndPreservesFollowingAppends() throws Exception {
        Path root = temporary.newFolder("corrupt").toPath(); //$NON-NLS-1$
        List<String> warnings = new ArrayList<>();
        MutableClock clock = new MutableClock(START);
        SessionStore store = store(root, clock, warnings::add);
        SessionMetadata metadata = store.create("build", "p", "m", "http://localhost", INSTANCE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        AgentMessage first = new AgentMessage.Text(AgentMessage.Role.USER, "first"); //$NON-NLS-1$
        store.append(metadata.id(), first);
        Path transcript = root.resolve(metadata.id() + ".jsonl"); //$NON-NLS-1$
        Files.writeString(transcript, "{\"content\":\"DO-NOT-ECHO", StandardCharsets.UTF_8, //$NON-NLS-1$
                java.nio.file.StandardOpenOption.APPEND);

        assertEquals(List.of(first), store.resume(metadata.id()).messages());
        assertTrue(warnings.stream().anyMatch(value -> value.contains("line 2"))); //$NON-NLS-1$
        assertFalse(warnings.stream().anyMatch(value -> value.contains("DO-NOT-ECHO"))); //$NON-NLS-1$

        clock.advanceSeconds(1);
        AgentMessage second = AgentMessage.Assistant.text("second"); //$NON-NLS-1$
        store.append(metadata.id(), second);
        ResumedSession resumed = store.resume(metadata.id());
        assertEquals(List.of(first, second), resumed.messages());
        assertEquals(2, resumed.metadata().messageCount());
    }

    @Test public void rejectsUnsupportedMetadataSchemaDeterministically() throws Exception {
        Path root = temporary.newFolder("schema").toPath(); //$NON-NLS-1$
        SessionStore store = store(root, Clock.fixed(START, ZoneOffset.UTC), warning -> { });
        SessionMetadata metadata = store.create("build", "p", "m", "http://localhost", INSTANCE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Path meta = root.resolve(metadata.id() + ".meta.json"); //$NON-NLS-1$
        Files.writeString(meta, Files.readString(meta).replace("\"schemaVersion\":1", "\"schemaVersion\":2")); //$NON-NLS-1$ //$NON-NLS-2$

        UnsupportedSessionSchemaException failure = assertThrows(
                UnsupportedSessionSchemaException.class, () -> store.resume(metadata.id()));
        assertEquals(2, failure.schemaVersion());
        assertEquals("Unsupported session schema version: 2", failure.getMessage()); //$NON-NLS-1$
        assertThrows(UnsupportedSessionSchemaException.class, store::list);
    }

    @Test public void listsNewestUpdatedSessionFirst() throws Exception {
        MutableClock clock = new MutableClock(START);
        SessionStore store = store(temporary.newFolder("sort").toPath(), clock, warning -> { }); //$NON-NLS-1$
        SessionMetadata older = store.create("build", "p", "m", "http://localhost", INSTANCE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        clock.advanceSeconds(10);
        SessionMetadata newer = store.create("build", "p", "m", "http://localhost", INSTANCE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        clock.advanceSeconds(10);
        older = store.append(older.id(), new AgentMessage.Text(AgentMessage.Role.USER, "updated")); //$NON-NLS-1$

        assertEquals(List.of(older.id(), newer.id()), store.list().stream().map(SessionMetadata::id).toList());
    }

    @Test public void fingerprintIsNormalizedStableAndNeverPersistsEndpointOrToken() throws Exception {
        String endpoint = "HTTPS://Example.COM:443/api/?token=top-secret"; //$NON-NLS-1$
        String normalizedVariant = "https://example.com/api?token=top-secret"; //$NON-NLS-1$
        String first = SessionStore.endpointFingerprint(endpoint, INSTANCE.toUpperCase());
        String second = SessionStore.endpointFingerprint(normalizedVariant, INSTANCE);
        assertEquals(first, second);
        assertEquals(64, first.length());
        assertNotEquals(first, SessionStore.endpointFingerprint(normalizedVariant,
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")); //$NON-NLS-1$

        Path root = temporary.newFolder("fingerprint").toPath(); //$NON-NLS-1$
        SessionStore store = store(root, Clock.fixed(START, ZoneOffset.UTC), warning -> { });
        SessionMetadata metadata = store.create("build", "p", "m", endpoint, INSTANCE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        store.append(metadata.id(), new AgentMessage.Text(AgentMessage.Role.USER, "hello")); //$NON-NLS-1$
        String persisted = Files.readString(root.resolve(metadata.id() + ".meta.json")) //$NON-NLS-1$
                + Files.readString(root.resolve(metadata.id() + ".jsonl")); //$NON-NLS-1$
        assertFalse(persisted.contains("Example.COM")); //$NON-NLS-1$
        assertFalse(persisted.contains("top-secret")); //$NON-NLS-1$
        assertTrue(persisted.contains(first));
    }

    @Test public void redactsMetadataMessagesToolCallsAndNestedToolResultsBeforeSerialization() throws Exception {
        String secret = "s3cr3t"; //$NON-NLS-1$
        Path root = temporary.newFolder("redaction").toPath(); //$NON-NLS-1$
        SessionStore store = new SessionStore(new PrivateFileWriter(root),
                value -> value.replace(secret, "<redacted>"), warning -> { }, //$NON-NLS-1$
                Clock.fixed(START, ZoneOffset.UTC));
        SessionMetadata metadata = store.create("mode-" + secret, "provider-" + secret, //$NON-NLS-1$ //$NON-NLS-2$
                "model-" + secret, "https://localhost/?token=" + secret, INSTANCE); //$NON-NLS-1$ //$NON-NLS-2$
        store.append(metadata.id(), new AgentMessage.Text(AgentMessage.Role.USER, "ask " + secret)); //$NON-NLS-1$
        store.append(metadata.id(), new AgentMessage.Assistant(Optional.of("answer " + secret), List.of( //$NON-NLS-1$
                new ToolCall("id-" + secret, "tool-" + secret, "{\"token\":\"" + secret + "\"}")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        JsonObject data = new JsonObject();
        data.addProperty("key-" + secret, "value-" + secret); //$NON-NLS-1$ //$NON-NLS-2$
        store.append(metadata.id(), new AgentMessage.Tool("id-" + secret, "tool-" + secret, //$NON-NLS-1$ //$NON-NLS-2$
                new ToolExecutionResult(false, "code-" + secret, "message-" + secret, data))); //$NON-NLS-1$ //$NON-NLS-2$

        String persisted = Files.readString(root.resolve(metadata.id() + ".meta.json")) //$NON-NLS-1$
                + Files.readString(root.resolve(metadata.id() + ".jsonl")); //$NON-NLS-1$
        assertFalse(persisted.contains(secret));
        assertTrue(persisted.contains("<redacted>")); //$NON-NLS-1$
        ResumedSession resumed = store.resume(metadata.id());
        assertEquals("ask <redacted>", ((AgentMessage.Text) resumed.messages().get(0)).content()); //$NON-NLS-1$
        AgentMessage.Tool tool = (AgentMessage.Tool) resumed.messages().get(2);
        assertTrue(tool.result().data().toString().contains("<redacted>")); //$NON-NLS-1$
        assertFalse(tool.result().data().toString().contains(secret));
    }

    @Test public void resumeReturnsSafeMismatchMetadata() throws Exception {
        Path root = temporary.newFolder("mismatch").toPath(); //$NON-NLS-1$
        SessionStore store = store(root, Clock.fixed(START, ZoneOffset.UTC), warning -> { });
        SessionMetadata metadata = store.create("build", "provider-a", "model-a", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "http://localhost:8080", INSTANCE); //$NON-NLS-1$
        SessionContext current = SessionContext.fromEndpoints("plan", "provider-b", "model-a", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "http://localhost:9090", INSTANCE, "https://provider.example/v2"); //$NON-NLS-1$

        SessionMismatch mismatch = store.resume(metadata.id(), current).mismatch();
        assertTrue(mismatch.present());
        assertEquals(List.of(SessionMismatch.Field.MODE, SessionMismatch.Field.PROVIDER,
                SessionMismatch.Field.MCP_ENDPOINT), mismatch.fields());
        assertFalse(mismatch.toString().contains("localhost")); //$NON-NLS-1$
    }

    @Test public void providerEndpointChangeDoesNotMasqueradeAsMcpMismatch() throws Exception {
        Path root = temporary.newFolder("split-provenance").toPath(); //$NON-NLS-1$
        SessionStore store = store(root, Clock.fixed(START, ZoneOffset.UTC), warning -> { });
        SessionContext original = SessionContext.fromEndpoints("standalone", "provider", "model", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "http://localhost:8765/mcp", INSTANCE, "https://provider.example/v1"); //$NON-NLS-1$ //$NON-NLS-2$
        SessionMetadata metadata = store.create(original);

        SessionContext providerOnly = SessionContext.fromEndpoints("standalone", "provider", "model", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "http://localhost:8765/mcp", INSTANCE, "https://provider.example/v2"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(store.resume(metadata.id(), providerOnly).mismatch().present());

        SessionContext changedMcp = SessionContext.fromEndpoints("standalone", "provider", "model", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "http://localhost:9876/mcp", INSTANCE, "https://provider.example/v1"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of(SessionMismatch.Field.MCP_ENDPOINT),
                store.resume(metadata.id(), changedMcp).mismatch().fields());

        JsonObject persisted = JsonParser.parseString(
                Files.readString(root.resolve(metadata.id() + ".meta.json"))).getAsJsonObject(); //$NON-NLS-1$
        assertTrue(persisted.has("mcpEndpointFingerprint")); //$NON-NLS-1$
        assertTrue(persisted.has("providerEndpointFingerprint")); //$NON-NLS-1$
        assertFalse(persisted.toString().contains("provider.example")); //$NON-NLS-1$
        assertFalse(persisted.toString().contains("localhost")); //$NON-NLS-1$
    }

    @Test public void legacySchemaV1WithoutSplitFieldsResumesWithoutFalseMcpWarning() throws Exception {
        Path root = temporary.newFolder("legacy-provenance").toPath(); //$NON-NLS-1$
        SessionStore store = store(root, Clock.fixed(START, ZoneOffset.UTC), warning -> { });
        SessionMetadata metadata = store.create("standalone", "provider", "model", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "https://provider.old/v1", INSTANCE); //$NON-NLS-1$
        Path meta = root.resolve(metadata.id() + ".meta.json"); //$NON-NLS-1$
        JsonObject legacy = JsonParser.parseString(Files.readString(meta)).getAsJsonObject();
        legacy.remove("mcpEndpointFingerprint"); //$NON-NLS-1$
        legacy.remove("providerEndpointFingerprint"); //$NON-NLS-1$
        Files.writeString(meta, legacy.toString(), StandardCharsets.UTF_8);

        SessionContext current = SessionContext.fromEndpoints("standalone", "provider", "model", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "http://localhost:8765/mcp", INSTANCE, "https://provider.new/v1"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(store.resume(metadata.id(), current).mismatch().present());
    }

    @Test public void legacyConnectedSchemaRetainsKnownMcpMismatchDetection() throws Exception {
        Path root = temporary.newFolder("legacy-connected-provenance").toPath(); //$NON-NLS-1$
        SessionStore store = store(root, Clock.fixed(START, ZoneOffset.UTC), warning -> { });
        SessionMetadata metadata = store.create("connected", "provider", "model", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "http://localhost:8765/mcp", INSTANCE); //$NON-NLS-1$
        Path meta = root.resolve(metadata.id() + ".meta.json"); //$NON-NLS-1$
        JsonObject legacy = JsonParser.parseString(Files.readString(meta)).getAsJsonObject();
        legacy.remove("mcpEndpointFingerprint"); //$NON-NLS-1$
        legacy.remove("providerEndpointFingerprint"); //$NON-NLS-1$
        Files.writeString(meta, legacy.toString(), StandardCharsets.UTF_8);

        SessionContext current = SessionContext.fromEndpoints("connected", "provider", "model", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "http://localhost:9876/mcp", INSTANCE, "http://localhost:9876/llm/v1"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of(SessionMismatch.Field.MCP_ENDPOINT),
                store.resume(metadata.id(), current).mismatch().fields());
    }

    @Test public void recoversCountWhenMetadataReplacementFailsAfterAppend() throws Exception {
        Path root = temporary.newFolder("failure").toPath(); //$NON-NLS-1$
        FailingSecondMetadataMove writer = new FailingSecondMetadataMove(root);
        SessionStore failing = new SessionStore(writer, FunctionIdentity.INSTANCE, warning -> { },
                Clock.fixed(START, ZoneOffset.UTC));
        SessionMetadata metadata = failing.create("build", "p", "m", "http://localhost", INSTANCE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertThrows(IOException.class, () -> failing.append(metadata.id(),
                new AgentMessage.Text(AgentMessage.Role.USER, "survives"))); //$NON-NLS-1$

        JsonObject onDisk = JsonParser.parseString(
                Files.readString(root.resolve(metadata.id() + ".meta.json"))).getAsJsonObject(); //$NON-NLS-1$
        assertEquals(0, onDisk.get("messageCount").getAsInt()); //$NON-NLS-1$
        SessionStore recovered = store(root, Clock.fixed(START, ZoneOffset.UTC), warning -> { });
        ResumedSession resumed = recovered.resume(metadata.id());
        assertEquals(1, resumed.metadata().messageCount());
        assertEquals(1, resumed.metadata().turns());
        assertEquals("survives", ((AgentMessage.Text) resumed.messages().get(0)).content()); //$NON-NLS-1$
    }

    @Test public void resolvesPortableDefaultBelowSuppliedHome() {
        Path expected = Path.of("test-home", ".codepilot1c", "sessions").toAbsolutePath().normalize(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(expected, SessionStore.defaultRoot(Path.of("test-home"))); //$NON-NLS-1$
    }

    @Test public void serializesConcurrentAppendsWithoutLosingMessagesOrCounts() throws Exception {
        Path root = temporary.newFolder("concurrent").toPath(); //$NON-NLS-1$
        SessionStore store = store(root, Clock.fixed(START, ZoneOffset.UTC), warning -> { });
        SessionMetadata metadata = store.create("build", "p", "m", "http://localhost", INSTANCE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        var executor = Executors.newFixedThreadPool(6);
        try {
            List<Callable<SessionMetadata>> appends = java.util.stream.IntStream.range(0, 24)
                    .mapToObj(index -> (Callable<SessionMetadata>) () -> store.append(metadata.id(),
                            new AgentMessage.Text(AgentMessage.Role.USER, "message-" + index))) //$NON-NLS-1$
                    .toList();
            for (var future : executor.invokeAll(appends)) future.get();
        } finally {
            executor.shutdownNow();
        }

        ResumedSession resumed = store.resume(metadata.id());
        assertEquals(24, resumed.metadata().messageCount());
        assertEquals(24, resumed.metadata().turns());
        assertEquals(24, resumed.messages().size());
        assertEquals(24, resumed.messages().stream()
                .map(AgentMessage.Text.class::cast).map(AgentMessage.Text::content).collect(java.util.stream.Collectors.toSet()).size());
    }

    private static SessionStore store(Path root, Clock clock, java.util.function.Consumer<String> warnings) {
        return new SessionStore(new PrivateFileWriter(root), FunctionIdentity.INSTANCE, warnings, clock);
    }

    private enum FunctionIdentity implements java.util.function.Function<String, String> {
        INSTANCE;
        @Override public String apply(String value) { return value; }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        MutableClock(Instant instant) { this.instant = instant; }
        void advanceSeconds(long seconds) { instant = instant.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    private static final class FailingSecondMetadataMove extends PrivateFileWriter {
        private int metadataMoves;
        FailingSecondMetadataMove(Path root) { super(root); }
        @Override protected void replaceTemporary(Path temporary, Path target) throws IOException {
            if (target.getFileName().toString().endsWith(".meta.json") && ++metadataMoves == 2) { //$NON-NLS-1$
                throw new IOException("injected metadata replacement failure"); //$NON-NLS-1$
            }
            super.replaceTemporary(temporary, target);
        }
    }
}
