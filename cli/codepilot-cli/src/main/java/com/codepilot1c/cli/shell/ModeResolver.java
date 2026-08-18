/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.codepilot1c.cli.shell.ShellOptions.Mode;

/** Deterministic connected/standalone/auto selection over injectable discovery seams. */
public final class ModeResolver {
    /** Four default two-second broker probes bound stale-candidate resolution to about eight seconds. */
    public static final int MAX_CONNECTED_CANDIDATES = 4;
    private final CandidateDiscovery discovery;
    private final ConnectedFactory connected;
    private final StandaloneFactory standalone;

    public ModeResolver(CandidateDiscovery discovery, ConnectedFactory connected,
            StandaloneFactory standalone) {
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.connected = Objects.requireNonNull(connected, "connected");
        this.standalone = Objects.requireNonNull(standalone, "standalone");
    }

    public ShellEnvironment resolve(ShellOptions options) throws ModeResolutionException {
        Objects.requireNonNull(options, "options");
        List<Candidate> candidates = List.copyOf(discovery.discover(options));
        if (options.mode() == Mode.STANDALONE) return standalone(options, candidates);

        List<String> diagnostics = new ArrayList<>();
        for (Candidate candidate : candidates.stream().limit(MAX_CONNECTED_CANDIDATES).toList()) {
            try {
                ShellEnvironment environment = connected.connect(candidate, options);
                if (!"connected".equals(environment.mode())) {
                    environment.close();
                    throw new IllegalStateException("connected factory returned another mode");
                }
                return environment;
            } catch (Exception failure) {
                diagnostics.add(candidate.label());
            }
        }
        if (options.mode() == Mode.CONNECTED) {
            throw new ModeResolutionException("No authenticated EDT LLM broker is available. "
                    + "Start/select EDT and verify the MCP bearer token.", diagnostics);
        }
        if (standalone.usable(options)) return standalone(options, candidates);
        throw new ModeResolutionException("Auto mode found no authenticated EDT LLM broker and no "
                + "usable explicit standalone provider. Configure --provider-endpoint and --model.",
                diagnostics);
    }

    private ShellEnvironment standalone(ShellOptions options, List<Candidate> candidates)
            throws ModeResolutionException {
        if (!standalone.usable(options)) {
            throw new ModeResolutionException("Standalone mode requires a usable explicit provider "
                    + "endpoint and model.", List.of());
        }
        try {
            return standalone.connect(options, candidates);
        } catch (Exception failure) {
            throw new ModeResolutionException("Standalone provider or MCP setup failed. "
                    + "Verify endpoint, credentials, and EDT availability.", List.of());
        }
    }

    public record Candidate(String endpoint, String instanceId, String label) {
        public Candidate {
            Objects.requireNonNull(endpoint, "endpoint");
            instanceId = instanceId == null ? "" : instanceId;
            label = label == null || label.isBlank() ? "endpoint" : label;
        }
    }

    @FunctionalInterface public interface CandidateDiscovery {
        List<Candidate> discover(ShellOptions options) throws ModeResolutionException;
    }

    @FunctionalInterface public interface ConnectedFactory {
        ShellEnvironment connect(Candidate candidate, ShellOptions options) throws Exception;
    }

    public interface StandaloneFactory {
        boolean usable(ShellOptions options);
        ShellEnvironment connect(ShellOptions options, List<Candidate> candidates) throws Exception;
    }

    public static final class ModeResolutionException extends Exception {
        private static final long serialVersionUID = 1L;
        private final List<String> attempted;
        public ModeResolutionException(String message, List<String> attempted) {
            super(message);
            this.attempted = List.copyOf(attempted);
        }
        public List<String> attempted() { return attempted; }
    }
}
