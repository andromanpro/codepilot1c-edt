package com.codepilot1c.core.edt.observability.eventlog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the classic text event log ({@code 1Cv8Log/1Cv8.lgf} + daily
 * {@code *.lgp} partitions) of a file infobase and answers filtered,
 * newest-first queries with bounded scanning.
 *
 * <p>The SQLite variant ({@code 1Cv8Log/1Cv8.lgd}) is intentionally out of
 * scope: this service reports it distinctly so the caller can suggest
 * switching the infobase to the text format.</p>
 */
public final class EventLogService {

    /** Hard cap on parsed records per query — CPU guard for huge logs. */
    public static final int DEFAULT_SCAN_CAP = 500_000;

    private static final Pattern FILE_CLAUSE = Pattern.compile("File=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE); //$NON-NLS-1$
    private static final Pattern PARTITION_NAME = Pattern.compile("(\\d{14})\\.lgp"); //$NON-NLS-1$

    /** Query filter; {@code null}/empty members mean "no restriction". */
    public static final class Query {
        public long sinceRaw; // yyyyMMddHHmmss literal, 0 = open
        public long untilRaw; // 0 = open
        public Set<String> severities; // decoded names: Error, Warning, ...
        public String eventContains;
        public String userContains;
        public String metadataContains;
        public String textContains;
        public int limit = 50;
        public int offset;
    }

    public static final class Result {
        public final List<EventLogRecord> records = new ArrayList<>();
        public long matched;
        public long scanned;
        public int partitionsScanned;
        public boolean hasMore;
        public boolean scanCapHit;
        public boolean partialTail;
    }

    private final int scanCap;

    public EventLogService() {
        this(DEFAULT_SCAN_CAP);
    }

    public EventLogService(int scanCap) {
        this.scanCap = scanCap;
    }

    /** Extracts the {@code File="..."} path from a 1C connection string, or {@code null}. */
    public static Path infobasePathFromConnectionString(String connectionString) {
        if (connectionString == null) {
            return null;
        }
        Matcher m = FILE_CLAUSE.matcher(connectionString);
        return m.find() ? Path.of(m.group(1)) : null;
    }

    public static Path logDirOf(Path infobaseDir) {
        return infobaseDir == null ? null : infobaseDir.resolve("1Cv8Log"); //$NON-NLS-1$
    }

    public static boolean isSqliteFormat(Path logDir) {
        return logDir != null && Files.isRegularFile(logDir.resolve("1Cv8.lgd")); //$NON-NLS-1$
    }

    /**
     * Runs the query over the log directory: partitions are visited newest
     * first, matches inside each partition are reversed to keep the overall
     * newest-first order, and the walk stops as soon as {@code offset+limit}
     * matches are collected (older partitions are then irrelevant).
     */
    public Result query(Path logDir, Query q) throws IOException {
        Result result = new Result();
        LgfCatalog refs = LgfCatalog.load(logDir.resolve("1Cv8.lgf")); //$NON-NLS-1$
        List<Path> partitions = partitionsNewestFirst(logDir, q);
        int needed = q.offset + Math.max(q.limit, 0);
        List<EventLogRecord> collected = new ArrayList<>();
        for (Path lgp : partitions) {
            if (collected.size() >= needed || result.scanned >= scanCap) {
                result.hasMore = result.hasMore || collected.size() >= needed;
                break;
            }
            result.partitionsScanned++;
            List<EventLogRecord> partitionMatches = new ArrayList<>();
            boolean torn = scanPartition(lgp, refs, q, result, partitionMatches);
            result.partialTail |= torn;
            Collections.reverse(partitionMatches);
            collected.addAll(partitionMatches);
        }
        result.matched = collected.size();
        int from = Math.min(q.offset, collected.size());
        int to = Math.min(from + Math.max(q.limit, 0), collected.size());
        result.records.addAll(collected.subList(from, to));
        result.hasMore = result.hasMore || to < collected.size();
        result.scanCapHit = result.scanned >= scanCap;
        return result;
    }

    /** @return true when the partition tail was torn mid-record (active file). */
    private boolean scanPartition(Path lgp, LgfCatalog refs, Query q, Result result,
            List<EventLogRecord> sink) throws IOException {
        try (BufferedReader br = Files.newBufferedReader(lgp, StandardCharsets.UTF_8);
                PushbackReader reader = new PushbackReader(br, 1)) {
            skipHeader(reader);
            while (result.scanned < scanCap) {
                LgValue rec;
                try {
                    rec = LgValue.readRecord(reader);
                } catch (LgValue.TruncatedRecordException e) {
                    return true;
                }
                if (rec == null) {
                    return false;
                }
                EventLogRecord ev = EventLogRecord.decode(rec, refs);
                if (ev == null) {
                    continue; // header block or malformed entry
                }
                result.scanned++;
                if (matches(ev, q)) {
                    sink.add(ev);
                }
            }
            return false;
        }
    }

    /**
     * The partition starts with {@code 1CV8LOG(ver 2.0)}, a UUID line and an
     * open-brace-free preamble; {@link LgValue#readRecord} already skips
     * anything before the first top-level brace, and the first brace block of
     * a partition is an ordinary record, so no extra work is needed. Kept as a
     * hook should the format grow a real header block.
     */
    private static void skipHeader(PushbackReader reader) {
        // intentionally empty
    }

    private static boolean matches(EventLogRecord ev, Query q) {
        if (q.sinceRaw > 0 && ev.dateRaw < q.sinceRaw) {
            return false;
        }
        if (q.untilRaw > 0 && ev.dateRaw > q.untilRaw) {
            return false;
        }
        if (q.severities != null && !q.severities.isEmpty()
                && (ev.severity == null || !q.severities.contains(ev.severity))) {
            return false;
        }
        if (!containsIgnoreCase(ev.event, q.eventContains)) {
            return false;
        }
        if (!containsIgnoreCase(ev.user, q.userContains)) {
            return false;
        }
        if (!containsIgnoreCase(ev.metadata, q.metadataContains)) {
            return false;
        }
        if (q.textContains != null && !q.textContains.isEmpty()) {
            return containsIgnoreCase(ev.comment, q.textContains)
                    || containsIgnoreCase(ev.dataPresentation, q.textContains)
                    || containsIgnoreCase(ev.dataValue, q.textContains);
        }
        return true;
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        if (needle == null || needle.isEmpty()) {
            return true;
        }
        return haystack != null
                && haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    /**
     * Partitions carry their day in the file name ({@code yyyyMMdd000000.lgp});
     * a partition is skipped when its whole day lies outside the query window.
     */
    private List<Path> partitionsNewestFirst(Path logDir, Query q) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(logDir)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(logDir, "*.lgp")) { //$NON-NLS-1$
            for (Path p : stream) {
                Matcher m = PARTITION_NAME.matcher(p.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                long dayStart = Long.parseLong(m.group(1));
                long dayEnd = dayStart + 235959; // same-day upper literal bound
                if (q.sinceRaw > 0 && dayEnd < q.sinceRaw) {
                    continue;
                }
                if (q.untilRaw > 0 && dayStart > q.untilRaw) {
                    continue;
                }
                out.add(p);
            }
        }
        out.sort((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()));
        return out;
    }
}
