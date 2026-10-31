/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.codepilot1c.cli.shell.ModeResolver.Candidate;
import com.codepilot1c.cli.shell.ModeResolver.ModeResolutionException;
import com.codepilot1c.cli.shell.broker.BrokerClient;
import com.codepilot1c.runtime.agent.AgentMessage;
import com.codepilot1c.runtime.agent.AgentModel;
import com.codepilot1c.runtime.agent.CancellationToken;
import com.codepilot1c.runtime.agent.StreamObserver;
import com.codepilot1c.runtime.agent.StreamingAgentModel;
import com.codepilot1c.runtime.agent.ToolDefinition;
import com.codepilot1c.runtime.agent.ToolExecutionResult;
import com.codepilot1c.runtime.agent.ToolRuntime;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

public class ModeResolverTest {
    private static final Candidate FIRST = new Candidate("http://localhost:1/mcp", "one", "first");
    private static final Candidate SECOND = new Candidate("http://localhost:2/mcp", "two", "second");

    @Test public void connectedAndAutoProbeDiscoveredCandidatesInOrder() throws Exception {
        List<String> probes = new ArrayList<>();
        ModeResolver resolver = new ModeResolver(options -> List.of(FIRST, SECOND),
                (candidate, options) -> {
                    probes.add(candidate.label());
                    if (candidate == FIRST) throw new IllegalStateException("unavailable");
                    return environment("connected");
                }, standalone(false));

        ShellEnvironment resolved = resolver.resolve(options(ShellOptions.Mode.AUTO, false));
        assertEquals("connected", resolved.mode());
        assertEquals(List.of("first", "second"), probes);
        resolved.close();

        probes.clear();
        ShellEnvironment explicit = resolver.resolve(options(ShellOptions.Mode.CONNECTED, false));
        assertEquals("connected", explicit.mode());
        assertEquals(List.of("first", "second"), probes);
        explicit.close();
    }

    @Test public void autoFallsBackOnlyForUsableExplicitStandaloneConfiguration() throws Exception {
        AtomicInteger standaloneConnections = new AtomicInteger();
        ModeResolver.StandaloneFactory standalone = new ModeResolver.StandaloneFactory() {
            @Override public boolean usable(ShellOptions options) { return options.providerEndpoint() != null; }
            @Override public ShellEnvironment connect(ShellOptions options, List<Candidate> candidates) {
                standaloneConnections.incrementAndGet();
                return environment("standalone");
            }
        };
        ModeResolver resolver = new ModeResolver(options -> List.of(FIRST),
                (candidate, options) -> { throw new IllegalStateException("no broker"); }, standalone);

        ShellEnvironment resolved = resolver.resolve(options(ShellOptions.Mode.AUTO, true));
        assertEquals("standalone", resolved.mode());
        assertEquals(1, standaloneConnections.get());
        resolved.close();

        ModeResolutionException failure = assertThrows(ModeResolutionException.class,
                () -> resolver.resolve(options(ShellOptions.Mode.AUTO, false)));
        assertTrue(failure.getMessage().contains("no authenticated EDT LLM broker"));
        assertEquals(List.of("first"), failure.attempted());
    }

    @Test public void explicitModesNeverSilentlyCrossFallbackBoundary() {
        AtomicInteger standaloneConnections = new AtomicInteger();
        ModeResolver resolver = new ModeResolver(options -> List.of(FIRST),
                (candidate, options) -> { throw new IllegalStateException("no broker"); },
                new ModeResolver.StandaloneFactory() {
                    @Override public boolean usable(ShellOptions options) { return true; }
                    @Override public ShellEnvironment connect(ShellOptions options, List<Candidate> candidates) {
                        standaloneConnections.incrementAndGet();
                        return environment("standalone");
                    }
                });
        assertThrows(ModeResolutionException.class,
                () -> resolver.resolve(options(ShellOptions.Mode.CONNECTED, true)));
        assertEquals(0, standaloneConnections.get());
        try {
            ShellEnvironment standalone = resolver.resolve(options(ShellOptions.Mode.STANDALONE, true));
            assertEquals("standalone", standalone.mode());
            standalone.close();
        } catch (ModeResolutionException failure) {
            throw new AssertionError(failure);
        }
        assertEquals(1, standaloneConnections.get());
    }

    @Test public void staleHeaderlessBrokerFallsThroughToNextCandidateWithinProbeBound()
            throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer stale = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stale.createContext("/llm/v1/capabilities", exchange -> {
            accepted.countDown();
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        stale.start();
        Candidate staleCandidate = new Candidate(
                "http://127.0.0.1:" + stale.getAddress().getPort() + "/mcp", //$NON-NLS-1$ //$NON-NLS-2$
                "stale", "stale broker"); //$NON-NLS-1$ //$NON-NLS-2$
        AtomicInteger attempts = new AtomicInteger();
        ModeResolver resolver = new ModeResolver(options -> List.of(staleCandidate, SECOND),
                (candidate, options) -> {
                    attempts.incrementAndGet();
                    if (candidate == staleCandidate) {
                        try (BrokerClient client = new BrokerClient(HttpClient.newHttpClient(),
                                URI.create(candidate.endpoint()), null, false,
                                Duration.ofMillis(200), Duration.ofMinutes(5))) {
                            client.probe().toCompletableFuture().get();
                        }
                        throw new AssertionError("stale probe unexpectedly succeeded"); //$NON-NLS-1$
                    }
                    return environment("connected"); //$NON-NLS-1$
                }, standalone(false));
        long started = System.nanoTime();
        try {
            ShellEnvironment resolved = resolver.resolve(options(ShellOptions.Mode.AUTO, false));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals("connected", resolved.mode()); //$NON-NLS-1$
            assertEquals(2, attempts.get());
            assertTrue(accepted.await(1, TimeUnit.SECONDS));
            assertTrue("fallback exceeded probe bound: " + elapsedMillis, elapsedMillis < 1500); //$NON-NLS-1$
            resolved.close();
        } finally {
            release.countDown();
            stale.stop(0);
        }
    }

    @Test public void candidateResolutionHasBoundedProbeCount() {
        List<Candidate> candidates = java.util.stream.IntStream.range(0, 7)
                .mapToObj(index -> new Candidate("http://localhost:" + (1000 + index) + "/mcp", //$NON-NLS-1$ //$NON-NLS-2$
                        "id-" + index, "candidate-" + index)) //$NON-NLS-1$ //$NON-NLS-2$
                .toList();
        AtomicInteger attempts = new AtomicInteger();
        ModeResolver resolver = new ModeResolver(options -> candidates,
                (candidate, options) -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("stale"); //$NON-NLS-1$
                }, standalone(false));

        assertThrows(ModeResolutionException.class,
                () -> resolver.resolve(options(ShellOptions.Mode.CONNECTED, false)));
        assertEquals(ModeResolver.MAX_CONNECTED_CANDIDATES, attempts.get());
    }

    private static ModeResolver.StandaloneFactory standalone(boolean usable) {
        return new ModeResolver.StandaloneFactory() {
            @Override public boolean usable(ShellOptions options) { return usable; }
            @Override public ShellEnvironment connect(ShellOptions options, List<Candidate> candidates) {
                return environment("standalone");
            }
        };
    }

    private static ShellOptions options(ShellOptions.Mode mode, boolean provider) {
        return new ShellOptions(mode, null, null, null, false,
                provider ? "openai-compatible" : null,
                provider ? "http://localhost:9999/v1" : null,
                provider ? "model" : null, null, false, 8, 30, null);
    }

    private static ShellEnvironment environment(String mode) {
        return new ShellEnvironment(mode, "provider", "model", "http://localhost/mcp", "instance",
                new StreamingAgentModel() {
                    @Override public CompletionStage<AgentMessage.Assistant> complete(
                            AgentModel.Request request, CancellationToken cancellation,
                            StreamObserver observer) {
                        return CompletableFuture.completedFuture(AgentMessage.Assistant.text("ok"));
                    }
                }, new EmptyTools(), () -> { });
    }

    private static final class EmptyTools implements ShellToolSession {
        private final ToolRuntime runtime = new ToolRuntime() {
            @Override public List<ToolDefinition> tools() { return List.of(); }
            @Override public CompletionStage<ToolExecutionResult> execute(
                    String name, JsonObject arguments, CancellationToken cancellation) {
                throw new AssertionError();
            }
        };
        @Override public ToolRuntime runtime() { return runtime; }
        @Override public CompletionStage<List<ToolDefinition>> refresh() {
            return CompletableFuture.completedFuture(List.of());
        }
        @Override public CompletionStage<Void> ping() { return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<ShellToolSession> reinitialize() {
            return CompletableFuture.completedFuture(this);
        }
        @Override public boolean isExpired(Throwable failure) { return false; }
        @Override public void close() { }
    }
}
