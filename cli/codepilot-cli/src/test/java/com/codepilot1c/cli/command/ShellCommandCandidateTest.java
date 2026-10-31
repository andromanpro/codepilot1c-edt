/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.junit.Test;

import com.codepilot1c.cli.CliConfiguration;
import com.codepilot1c.cli.CliServices;
import com.codepilot1c.cli.EndpointProbe;
import com.codepilot1c.cli.discovery.EdtInstallationDiscovery;
import com.codepilot1c.cli.platform.HostSystem;
import com.codepilot1c.cli.shell.ModeResolver;
import com.codepilot1c.cli.shell.ModeResolver.Candidate;
import com.codepilot1c.cli.shell.ShellOptions;
import com.codepilot1c.cli.supervisor.DefaultSupervisorFileSystem;
import com.codepilot1c.cli.supervisor.InstanceRecord;
import com.codepilot1c.cli.supervisor.InstanceRegistry;

public class ShellCommandCandidateTest {
    @Test public void noUserConfigurationOrdersNewestRegistryBeforeDefault()
            throws Exception {
        Path home = Files.createTempDirectory("codepilot-shell-candidates-");
        try {
            TestHost host = new TestHost(home, null, null);
            ShellCommand command = command(host);
            InstanceRegistry registry = new InstanceRegistry(new DefaultSupervisorFileSystem(),
                    home.resolve(".codepilot1c").resolve("instances"));
            Instant now = Instant.now();
            registry.write(record(9100, now));
            registry.write(record(9101, now.plusSeconds(1)));

            List<Candidate> candidates = command.discoverCandidates(options(null, null));

            assertEquals(List.of(
                    "http://127.0.0.1:9101/mcp",
                    "http://127.0.0.1:9100/mcp",
                    "http://127.0.0.1:8765/mcp"),
                    candidates.stream().map(Candidate::endpoint).toList());
            assertEquals("registered EDT instance", candidates.get(0).label());
            assertEquals("default endpoint", candidates.get(2).label());
        } finally {
            deleteTree(home);
        }
    }

    @Test public void actualConfiguredEndpointPrecedesFourStaleRegistryRecordsWithinProbeBound()
            throws Exception {
        Path home = Files.createTempDirectory("codepilot-shell-starvation-");
        try {
            TestHost host = new TestHost(home, "http://127.0.0.1:9200", null);
            ShellCommand command = command(host);
            InstanceRegistry registry = new InstanceRegistry(new DefaultSupervisorFileSystem(),
                    home.resolve(".codepilot1c").resolve("instances"));
            Instant now = Instant.now();
            for (int index = 0; index < 4; index++) {
                registry.write(record(9100 + index, now.plusSeconds(index + 1)));
            }

            List<Candidate> candidates = command.discoverCandidates(options(null, null));

            assertEquals(6, candidates.size());
            assertEquals("http://127.0.0.1:9200/mcp", candidates.get(0).endpoint());
            assertEquals("configured endpoint", candidates.get(0).label());
            assertTrue(candidates.stream().limit(ModeResolver.MAX_CONNECTED_CANDIDATES)
                    .anyMatch(candidate -> candidate.endpoint().equals("http://127.0.0.1:9200/mcp")));
            assertEquals("http://127.0.0.1:8765/mcp",
                    candidates.get(candidates.size() - 1).endpoint());
        } finally {
            deleteTree(home);
        }
    }

    @Test public void endpointPropertyPrecedesEnvironment() throws Exception {
        Path home = Files.createTempDirectory("codepilot-shell-precedence-");
        try {
            TestHost host = new TestHost(home, "http://127.0.0.1:9300",
                    "http://127.0.0.1:9400");

            List<Candidate> candidates = command(host).discoverCandidates(options(null, null));

            assertEquals("http://127.0.0.1:9300/mcp", candidates.get(0).endpoint());
            assertTrue(candidates.stream().noneMatch(candidate ->
                    candidate.endpoint().equals("http://127.0.0.1:9400/mcp")));
            assertEquals("http://127.0.0.1:8765/mcp", candidates.get(1).endpoint());
        } finally {
            deleteTree(home);
        }
    }

    @Test public void userConfiguredRegistryAndDefaultCandidatesAreDeduplicated() throws Exception {
        Path home = Files.createTempDirectory("codepilot-shell-dedup-");
        try {
            TestHost host = new TestHost(home, CliConfiguration.DEFAULT_ENDPOINT, null);
            InstanceRegistry registry = new InstanceRegistry(new DefaultSupervisorFileSystem(),
                    home.resolve(".codepilot1c").resolve("instances"));
            registry.write(record(8765, Instant.now()));

            List<Candidate> candidates = command(host).discoverCandidates(options(null, null));

            assertEquals(1, candidates.size());
            assertEquals("http://127.0.0.1:8765/mcp", candidates.get(0).endpoint());
            assertEquals("configured endpoint", candidates.get(0).label());
        } finally {
            deleteTree(home);
        }
    }

    @Test public void explicitEndpointAndInstanceSelectionsRemainSingleDeterministicCandidates()
            throws Exception {
        Path home = Files.createTempDirectory("codepilot-shell-explicit-");
        try {
            TestHost host = new TestHost(home, "http://127.0.0.1:9200", null);
            ShellCommand command = command(host);
            InstanceRecord selected = record(9300, Instant.now());
            new InstanceRegistry(new DefaultSupervisorFileSystem(),
                    home.resolve(".codepilot1c").resolve("instances")).write(selected);

            List<Candidate> endpoint = command.discoverCandidates(
                    options(null, "http://127.0.0.1:9400"));
            assertEquals(1, endpoint.size());
            assertEquals("http://127.0.0.1:9400/mcp", endpoint.get(0).endpoint());

            List<Candidate> instance = command.discoverCandidates(
                    options(selected.instanceId(), null));
            assertEquals(1, instance.size());
            assertEquals("http://127.0.0.1:9300/mcp", instance.get(0).endpoint());
            assertEquals(selected.instanceId(), instance.get(0).instanceId());
        } finally {
            deleteTree(home);
        }
    }

    private static ShellCommand command(TestHost host) {
        StringWriter output = new StringWriter();
        CliServices services = new CliServices(host, new EdtInstallationDiscovery(host),
                new CliConfiguration(host), endpoint -> new EndpointProbe.ProbeResult(true, 200, "HTTP 200"),
                new PrintWriter(output, true), new PrintWriter(output, true), "test");
        return new ShellCommand(new RootCommand(services));
    }

    private static ShellOptions options(String instanceId, String endpoint) {
        return new ShellOptions(ShellOptions.Mode.AUTO, instanceId, endpoint, null, false,
                null, null, null, null, false, 16, 300, null);
    }

    private static InstanceRecord record(int port, Instant startedAt) {
        return new InstanceRecord(InstanceRecord.SCHEMA_VERSION, UUID.randomUUID().toString(),
                123L, port, "http://127.0.0.1:" + port, "/workspace", "/edt", "gui", "external",
                startedAt, "test", "bearer", null, List.of("llm.v1"));
    }

    private static void deleteTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static final class TestHost implements HostSystem {
        private final Path home;
        private final String propertyEndpoint;
        private final String environmentEndpoint;
        TestHost(Path home, String propertyEndpoint, String environmentEndpoint) {
            this.home = home;
            this.propertyEndpoint = propertyEndpoint;
            this.environmentEndpoint = environmentEndpoint;
        }
        @Override public String osName() { return "Linux"; }
        @Override public String javaVersion() { return "17"; }
        @Override public String userHome() { return home.toString(); }
        @Override public String environment(String name) {
            return "CODEPILOT_ENDPOINT".equals(name) ? environmentEndpoint : null;
        }
        @Override public String systemProperty(String name) {
            return "codepilot.endpoint".equals(name) ? propertyEndpoint : null;
        }
        @Override public boolean isDirectory(String path) { return false; }
        @Override public boolean isRegularFile(String path) { return false; }
        @Override public boolean isReadable(String path) { return false; }
        @Override public List<String> children(String directory) { return List.of(); }
    }
}
