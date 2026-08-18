package com.codepilot1c.core.edt.metadata.scope;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Content snapshots of a project's {@code src} tree, used to answer one question
 * after a mutation: <em>did the change touch exactly what it promised, and nothing
 * else?</em>
 *
 * <p>A green round-trip only proves the sources still load. It says nothing about
 * collateral damage — a rename that also rewrote string literals in borrowed BSP
 * code, or a form edit that quietly re-serialized a neighbouring object. This
 * service compares a before/after file-hash map against the caller's declared
 * scope and reports what fell outside it.</p>
 */
public final class ChangeScopeService {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String SNAPSHOT_DIR = ".codepilot/scope"; //$NON-NLS-1$

    /** File path (project-relative, forward slashes) → content hash. */
    public static final class Snapshot {
        public String projectName;
        public String scopeId;
        public long createdAtEpochMs;
        public Map<String, String> files = new TreeMap<>();
    }

    public static final class Verdict {
        public final List<String> expectedAndChanged = new ArrayList<>();
        public final List<String> expectedButUntouched = new ArrayList<>();
        public final List<String> unexpectedlyChanged = new ArrayList<>();
        public final List<String> unexpectedlyAdded = new ArrayList<>();
        public final List<String> unexpectedlyRemoved = new ArrayList<>();
        public int filesScanned;

        /** Clean means: every declared file changed, and nothing else moved. */
        public boolean isClean() {
            return expectedButUntouched.isEmpty()
                    && unexpectedlyChanged.isEmpty()
                    && unexpectedlyAdded.isEmpty()
                    && unexpectedlyRemoved.isEmpty();
        }
    }

    private final Path workspaceRoot;

    public ChangeScopeService(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    /**
     * Hashes every file under {@code <project>/src}. The EDT working tree is the
     * subject here, so metadata/bin caches are out of scope by construction.
     */
    public Snapshot capture(String projectName, String scopeId) throws IOException {
        Path srcRoot = workspaceRoot.resolve(projectName).resolve("src"); //$NON-NLS-1$
        if (!Files.isDirectory(srcRoot)) {
            throw new IOException("Project src directory not found: " + srcRoot); //$NON-NLS-1$
        }
        Snapshot snapshot = new Snapshot();
        snapshot.projectName = projectName;
        snapshot.scopeId = scopeId;
        snapshot.createdAtEpochMs = System.currentTimeMillis();
        try (Stream<Path> walk = Files.walk(srcRoot)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                snapshot.files.put(relative(srcRoot, p), hash(p));
            }
        }
        return snapshot;
    }

    public Path store(Snapshot snapshot) throws IOException {
        Path dir = workspaceRoot.resolve(SNAPSHOT_DIR);
        Files.createDirectories(dir);
        Path file = dir.resolve(snapshot.projectName + "__" + safe(snapshot.scopeId) + ".json"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(file, GSON.toJson(snapshot), StandardCharsets.UTF_8);
        return file;
    }

    public Snapshot load(String projectName, String scopeId) throws IOException {
        Path file = workspaceRoot.resolve(SNAPSHOT_DIR)
                .resolve(projectName + "__" + safe(scopeId) + ".json"); //$NON-NLS-1$ //$NON-NLS-2$
        if (!Files.isRegularFile(file)) {
            throw new IOException("No baseline snapshot for scope '" + scopeId //$NON-NLS-1$
                    + "'. Capture one before the mutation."); //$NON-NLS-1$
        }
        return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Snapshot.class);
    }

    /**
     * Compares the baseline with the current tree.
     *
     * @param expected project-relative paths the caller intended to change; a
     *        path ending in {@code /} is treated as a directory prefix, so a
     *        whole object folder can be declared in one line.
     */
    public Verdict verify(Snapshot baseline, Snapshot current, List<String> expected) {
        Verdict verdict = new Verdict();
        verdict.filesScanned = current.files.size();
        Set<String> matchedExpectations = new HashSet<>();

        Map<String, String> before = baseline.files == null ? new HashMap<>() : baseline.files;
        Map<String, String> after = current.files;

        for (Map.Entry<String, String> e : after.entrySet()) {
            String path = e.getKey();
            String beforeHash = before.get(path);
            String expectation = matchExpectation(path, expected);
            if (beforeHash == null) {
                if (expectation == null) {
                    verdict.unexpectedlyAdded.add(path);
                } else {
                    verdict.expectedAndChanged.add(path);
                    matchedExpectations.add(expectation);
                }
            } else if (!beforeHash.equals(e.getValue())) {
                if (expectation == null) {
                    verdict.unexpectedlyChanged.add(path);
                } else {
                    verdict.expectedAndChanged.add(path);
                    matchedExpectations.add(expectation);
                }
            }
        }
        for (String path : before.keySet()) {
            if (!after.containsKey(path)) {
                String expectation = matchExpectation(path, expected);
                if (expectation == null) {
                    verdict.unexpectedlyRemoved.add(path);
                } else {
                    verdict.expectedAndChanged.add(path);
                    matchedExpectations.add(expectation);
                }
            }
        }
        // A declaration that matched nothing is as suspicious as an unexpected write:
        // it means the mutation did not do what the caller believed it did.
        for (String expectation : expected) {
            if (!matchedExpectations.contains(expectation)) {
                verdict.expectedButUntouched.add(expectation);
            }
        }
        verdict.expectedAndChanged.sort(Comparator.naturalOrder());
        verdict.unexpectedlyChanged.sort(Comparator.naturalOrder());
        verdict.unexpectedlyAdded.sort(Comparator.naturalOrder());
        verdict.unexpectedlyRemoved.sort(Comparator.naturalOrder());
        return verdict;
    }

    /** Returns the declaration covering the path, or {@code null} when out of scope. */
    private static String matchExpectation(String path, List<String> expected) {
        String normalized = normalize(path);
        for (String raw : expected) {
            String candidate = normalize(raw);
            if (candidate.isEmpty()) {
                continue;
            }
            if (candidate.endsWith("/")) { //$NON-NLS-1$
                if (normalized.startsWith(candidate)) {
                    return raw;
                }
            } else if (normalized.equals(candidate) || normalized.endsWith("/" + candidate)) { //$NON-NLS-1$
                return raw;
            }
        }
        return null;
    }

    private static String normalize(String path) {
        String s = path.replace('\\', '/').trim();
        while (s.startsWith("/")) { //$NON-NLS-1$
            s = s.substring(1);
        }
        return s;
    }

    private static String relative(Path srcRoot, Path file) {
        return srcRoot.relativize(file).toString().replace('\\', '/');
    }

    private static String safe(String scopeId) {
        return scopeId == null || scopeId.isBlank()
                ? "default" //$NON-NLS-1$
                : scopeId.replaceAll("[^A-Za-z0-9_.-]", "_"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String hash(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
            try (InputStream in = Files.newInputStream(file);
                    DigestInputStream dis = new DigestInputStream(in, digest)) {
                byte[] buf = new byte[8192];
                while (dis.read(buf) != -1) {
                    // digest is updated by the stream
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e); //$NON-NLS-1$
        }
    }
}
