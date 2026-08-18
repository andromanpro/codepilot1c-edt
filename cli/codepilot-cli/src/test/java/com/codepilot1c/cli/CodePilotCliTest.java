/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.codepilot1c.cli.EndpointProbe.ProbeResult;
import com.codepilot1c.cli.discovery.EdtInstallationDiscovery;
import com.codepilot1c.cli.supervisor.DefaultSupervisorFileSystem;
import com.codepilot1c.cli.supervisor.InstanceRecord;
import com.codepilot1c.cli.supervisor.InstanceRegistry;

public class CodePilotCliTest {
    private static final String BROKER_CAPABILITIES = """
            {"schemaVersion":1,"maxSchemaVersion":1,"chat":true,"streaming":true,
             "provider":{"id":"active","name":"Active Provider","type":"openai_compatible",
                         "model":"safe-model","streamingEnabled":true}}
            """;

    @Test public void exitCodeContractIsStable() {
        assertEquals(0, ExitCodes.OK);
        assertEquals(1, ExitCodes.FAILURE);
        assertEquals(2, ExitCodes.USAGE);
        assertEquals(3, ExitCodes.AUTH);
        assertEquals(4, ExitCodes.EDT_UNAVAILABLE);
    }

    @Test public void parsesVersionInTextAndJson() {
        Fixture fixture = new Fixture();
        assertEquals(ExitCodes.OK, fixture.execute("version"));
        assertEquals("codepilot 9.8.7\n", fixture.out());

        fixture.reset();
        assertEquals(ExitCodes.OK, fixture.execute("--output", "json", "version"));
        assertEquals("{\"command\":\"version\",\"version\":\"9.8.7\"}\n", fixture.out());
    }

    @Test public void returnsStableUsageAndRequiredLifecycleArguments() {
        Fixture fixture = new Fixture();
        assertEquals(ExitCodes.USAGE, fixture.execute("unknown"));
        assertTrue(fixture.err().startsWith("error[usage]:"));

        fixture.reset();
        assertEquals(ExitCodes.USAGE, fixture.execute("--output", "json", "edt", "start"));
        assertTrue(fixture.err().contains("--workspace"));

        fixture.reset();
        assertEquals(ExitCodes.USAGE, fixture.execute("edt", "stop"));
        assertTrue(fixture.err().contains("--id=<id> | --all"));
    }

    @Test public void groupHelpIsAvailable() {
        Fixture fixture = new Fixture();
        assertEquals(ExitCodes.OK, fixture.execute("edt", "--help"));
        assertTrue(fixture.out().contains("Commands:"));
        assertTrue(fixture.out().contains("installations"));
    }

    @Test public void doctorWithoutRegistryRecordProbesAndKeepsAdditiveJsonContract() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (LocalServer server = new LocalServer(exchange -> {
            requests.incrementAndGet();
            send(exchange, 404, "old-plugin-body-secret");
        }); Fixture fixture = new Fixture()) {
            fixture.healthyEdt();
            fixture.host.properties.put("codepilot.endpoint", server.endpoint());
            fixture.probe = endpoint -> new ProbeResult(true, 200, "HTTP 200");

            assertEquals(ExitCodes.OK, fixture.execute("--output", "json", "doctor"));
            assertTrue(fixture.out().contains("\"status\":\"ok\""));
            assertTrue(fixture.out().contains("\"name\":\"java\""));
            assertTrue(fixture.out().contains("\"name\":\"edt\""));
            assertTrue(fixture.out().contains("\"name\":\"config\""));
            assertTrue(fixture.out().contains("\"name\":\"endpoint\""));
            assertTrue(fixture.out().contains("\"name\":\"broker\""));
            assertTrue(fixture.out().contains("\"code\":\"broker_not_advertised\""));
            assertTrue(!fixture.out().contains("old-plugin-body-secret"));
            assertEquals(1, requests.get());
        }
    }

    @Test public void doctorReturnsUnavailableWhenPrerequisitesFail() {
        Fixture fixture = new Fixture();
        fixture.host.java = "11.0.24";
        fixture.probe = endpoint -> new ProbeResult(false, 0, "ConnectException");

        assertEquals(ExitCodes.EDT_UNAVAILABLE, fixture.execute("doctor"));
        assertTrue(fixture.out().contains("java FAIL java_too_old"));
        assertTrue(fixture.out().contains("edt FAIL edt_not_found"));
        assertTrue(fixture.out().contains("endpoint FAIL endpoint_unavailable"));
    }

    @Test public void statusDoesNotClaimRunningWhenEndpointIsDown() {
        Fixture fixture = new Fixture();
        fixture.probe = endpoint -> new ProbeResult(false, 503, "HTTP 503");
        assertEquals(ExitCodes.EDT_UNAVAILABLE, fixture.execute("edt", "status"));
        assertTrue(fixture.out().startsWith("degraded:"));
    }

    @Test public void invalidEndpointIsUsageErrorWithoutProbe() {
        Fixture fixture = new Fixture();
        fixture.host.properties.put("codepilot.endpoint", "not a URI");
        fixture.probe = endpoint -> { throw new AssertionError("probe must not run"); };
        assertEquals(ExitCodes.USAGE, fixture.execute("edt", "status"));
        assertTrue(fixture.out().startsWith("error[invalid_endpoint]:"));
    }

    @Test public void endpointCredentialsAreRejectedAndNeverPrinted() {
        Fixture fixture = new Fixture();
        fixture.host.properties.put("codepilot.endpoint", "http://secret-value@localhost:8765");
        assertEquals(ExitCodes.USAGE, fixture.execute("--output", "json", "edt", "status"));
        assertTrue(fixture.out().contains("\"endpoint\":\"<invalid>\""));
        assertTrue(!fixture.out().contains("secret-value"));
    }

    @Test public void doctorProbesOldRecordAndReportsReachableBrokerInTextAndJson() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (LocalServer server = new LocalServer(exchange -> {
            requests.incrementAndGet();
            send(exchange, 200, BROKER_CAPABILITIES);
        }); Fixture fixture = new Fixture()) {
            fixture.healthyEdt();
            fixture.register(server.port(), List.of());
            fixture.host.properties.put("codepilot.endpoint", server.endpoint());
            fixture.probe = endpoint -> new ProbeResult(true, 200, "HTTP 200");

            assertDoctorTextAndJson(fixture, ExitCodes.OK, "pass", "broker_ready");
            assertEquals(2, requests.get());
        }
    }

    @Test public void doctorTreatsOldRecord404AsSupportedInTextAndJson() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        String bodySecret = "disabled-broker-body-secret";
        try (LocalServer server = new LocalServer(exchange -> {
            requests.incrementAndGet();
            send(exchange, 404, bodySecret);
        }); Fixture fixture = new Fixture()) {
            fixture.healthyEdt();
            fixture.register(server.port(), List.of());
            fixture.host.properties.put("codepilot.endpoint", server.endpoint());
            fixture.probe = endpoint -> new ProbeResult(true, 200, "HTTP 200");

            assertDoctorTextAndJson(fixture, ExitCodes.OK, "pass", "broker_not_advertised");
            assertTrue(!fixture.out().contains(bodySecret));
            assertEquals(2, requests.get());
        }
    }

    @Test public void doctorProbesMissingBrokerMetadataAndReportsUnreachableInTextAndJson()
            throws Exception {
        int port;
        try (LocalServer server = new LocalServer(exchange -> send(exchange, 200, BROKER_CAPABILITIES))) {
            port = server.port();
        }
        try (Fixture fixture = new Fixture()) {
            fixture.healthyEdt();
            fixture.register(port, List.of());
            fixture.host.properties.put("codepilot.endpoint", "http://127.0.0.1:" + port);
            fixture.probe = endpoint -> new ProbeResult(false, 0, "ConnectException");

            assertDoctorTextAndJson(fixture, ExitCodes.EDT_UNAVAILABLE, "fail", "broker_unreachable");
            assertTrue(!fixture.out().contains(Integer.toString(port)));
        }
    }

    @Test public void doctorProbesAdvertisedScalarAndReportsSuccessInTextAndJson() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (LocalServer server = new LocalServer(exchange -> {
            requests.incrementAndGet();
            send(exchange, 200, BROKER_CAPABILITIES);
        }); Fixture fixture = new Fixture()) {
            fixture.healthyEdt();
            fixture.register(server.port(), List.of("llm.v1"));
            fixture.host.properties.put("codepilot.endpoint", server.endpoint());
            fixture.probe = endpoint -> new ProbeResult(true, 200, "HTTP 200");

            assertDoctorTextAndJson(fixture, ExitCodes.OK, "pass", "broker_ready");
            assertEquals(2, requests.get());
        }
    }

    @Test public void doctorSkipsProbeForExplicitDisabledScalarInTextAndJson() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (LocalServer server = new LocalServer(exchange -> {
            requests.incrementAndGet();
            send(exchange, 500, "must-not-be-read");
        }); Fixture fixture = new Fixture()) {
            fixture.healthyEdt();
            fixture.registerBrokerVersion(server.port(), 0);
            fixture.host.properties.put("codepilot.endpoint", server.endpoint());
            fixture.probe = endpoint -> new ProbeResult(true, 200, "HTTP 200");

            assertDoctorTextAndJson(fixture, ExitCodes.OK, "pass", "broker_not_advertised");
            assertEquals(0, requests.get());
        }
    }

    @Test public void doctorReportsProviderUnavailableWithoutLeakingBrokerResponse() throws Exception {
        String bearer = "doctor-bearer-secret";
        try (LocalServer server = new LocalServer(exchange -> {
            assertEquals("Bearer " + bearer, exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().add("X-Provider-Key", "custom-header-secret");
            send(exchange, 503, "{\"apiKey\":\"body-api-secret\","
                    + "\"baseUrl\":\"https://user:password@provider.example/private\"}");
        }); Fixture fixture = new Fixture()) {
            fixture.healthyEdt();
            fixture.register(server.port(), List.of("llm.v1"));
            fixture.host.properties.put("codepilot.endpoint", server.endpoint());
            fixture.host.properties.put("codepilot.mcp.bearerToken", bearer);
            fixture.probe = endpoint -> new ProbeResult(true, 200, "HTTP 200");

            assertEquals(ExitCodes.EDT_UNAVAILABLE,
                    fixture.execute("--output", "json", "doctor"));
            assertTrue(fixture.out().contains("\"code\":\"provider_unavailable\""));
            assertTrue(fixture.out().contains("configure and select a provider in EDT"));
            assertTrue(!fixture.out().contains(bearer));
            assertTrue(!fixture.out().contains("custom-header-secret"));
            assertTrue(!fixture.out().contains("body-api-secret"));
            assertTrue(!fixture.out().contains("provider.example"));
            assertTrue(!fixture.out().contains("password"));
        }
    }

    @Test public void statusDisplaysLlmCapabilityOnlyWhenAdvertised() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.register(8765, List.of("llm.v1"));
            assertEquals(ExitCodes.EDT_UNAVAILABLE,
                    fixture.execute("--output", "json", "edt", "status", "--all"));
            assertTrue(fixture.out().contains("\"llmBrokerVersion\":1"));

            fixture.reset();
            assertEquals(ExitCodes.EDT_UNAVAILABLE, fixture.execute("edt", "status", "--all"));
            assertTrue(fixture.out().contains(" [llm.v1]"));

            fixture.reset();
            fixture.register(8765, List.of());
            assertEquals(ExitCodes.EDT_UNAVAILABLE, fixture.execute("edt", "status", "--all"));
            assertTrue(!fixture.out().contains("llm.v1"));
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static void assertDoctorTextAndJson(Fixture fixture, int exitCode,
            String jsonStatus, String code) {
        assertEquals(exitCode, fixture.execute("doctor"));
        assertTrue(fixture.out().contains("broker " + ("pass".equals(jsonStatus) ? "PASS" : "FAIL")
                + " " + code));

        fixture.reset();
        assertEquals(exitCode, fixture.execute("--output", "json", "doctor"));
        assertTrue(fixture.out().contains("\"name\":\"broker\",\"status\":\"" + jsonStatus
                + "\",\"code\":\"" + code + "\""));
    }

    @FunctionalInterface private interface Handler { void handle(HttpExchange exchange) throws IOException; }

    private static final class LocalServer implements AutoCloseable {
        private final HttpServer server;
        LocalServer(Handler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/llm/v1/capabilities", handler::handle);
            server.start();
        }
        int port() { return server.getAddress().getPort(); }
        String endpoint() { return "http://127.0.0.1:" + port(); }
        @Override public void close() { server.stop(0); }
    }

    private static final class Fixture implements AutoCloseable {
        final FakeHostSystem host = new FakeHostSystem();
        EndpointProbe probe = endpoint -> new ProbeResult(true, 200, "HTTP 200");
        StringWriter output = new StringWriter();
        StringWriter errors = new StringWriter();
        Path temporaryHome;
        String registeredId;

        int execute(String... args) {
            CliServices services = new CliServices(host, new EdtInstallationDiscovery(host), new CliConfiguration(host),
                    probe, new PrintWriter(output, true), new PrintWriter(errors, true), "9.8.7");
            return CodePilotCli.execute(services, args);
        }

        String out() { return output.toString().replace("\r\n", "\n"); }
        String err() { return errors.toString().replace("\r\n", "\n"); }
        void reset() { output = new StringWriter(); errors = new StringWriter(); }

        void healthyEdt() {
            host.properties.put("edt.home", "/opt/edt");
            host.directory("/opt/edt");
            host.file("/opt/edt/1cedt");
        }

        void register(int port, List<String> capabilities) throws IOException {
            if (temporaryHome == null) {
                temporaryHome = Files.createTempDirectory("codepilot-doctor-contract-");
                host.home = temporaryHome.toString();
            }
            Path directory = temporaryHome.resolve(".codepilot1c").resolve("instances");
            InstanceRegistry registry = new InstanceRegistry(new DefaultSupervisorFileSystem(), directory);
            if (registeredId == null) registeredId = UUID.randomUUID().toString();
            registry.write(new InstanceRecord(InstanceRecord.SCHEMA_VERSION, registeredId,
                    Math.max(1, ProcessHandle.current().pid()), port, "http://127.0.0.1:" + port,
                    "/workspace", "/edt", "gui", "external", Instant.now(), "9.8.7", "bearer",
                    null, capabilities));
        }

        void registerBrokerVersion(int port, int version) throws IOException {
            register(port, List.of());
            Path record = temporaryHome.resolve(".codepilot1c").resolve("instances")
                    .resolve(registeredId + ".json");
            String json = Files.readString(record, StandardCharsets.UTF_8).stripTrailing();
            Files.writeString(record, json.substring(0, json.length() - 1)
                    + ",\"llmBrokerVersion\":" + version + "}\n", StandardCharsets.UTF_8);
        }

        @Override public void close() throws IOException {
            if (temporaryHome == null) return;
            try (var paths = Files.walk(temporaryHome)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
