package com.codepilot1c.core.edt.forms;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;

/** Pure-text, veto-first planner for placing a generated stub in a top-level region. */
public final class BslRegionPlacement {

    private static final int REGION_PATTERN_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern OPEN_REGION = Pattern.compile(
            "^[\\t ]*(?:" //$NON-NLS-1$
                    + Pattern.quote(BslKeywords.regionDirective(ScriptVariant.RUSSIAN))
                    + "|" //$NON-NLS-1$
                    + Pattern.quote(BslKeywords.regionDirective(ScriptVariant.ENGLISH))
                    + ")[\\t ]+(.+?)[\\t ]*$", //$NON-NLS-1$
            REGION_PATTERN_FLAGS);
    private static final Pattern END_REGION = Pattern.compile(
            "^[\\t ]*(?:" //$NON-NLS-1$
                    + Pattern.quote(BslKeywords.endRegionDirective(ScriptVariant.RUSSIAN))
                    + "|" //$NON-NLS-1$
                    + Pattern.quote(BslKeywords.endRegionDirective(ScriptVariant.ENGLISH))
                    + ")(?:[\\t ]*//.*)?[\\t ]*$", //$NON-NLS-1$
            REGION_PATTERN_FLAGS);

    public Optional<Insertion> plan(
            String moduleText,
            String regionCanonicalName,
            int stdIndex,
            String stubProcedureText,
            ScriptVariant variant) {
        Objects.requireNonNull(moduleText, "moduleText"); //$NON-NLS-1$
        Objects.requireNonNull(regionCanonicalName, "regionCanonicalName"); //$NON-NLS-1$
        Objects.requireNonNull(stubProcedureText, "stubProcedureText"); //$NON-NLS-1$
        ScanResult scan = scan(moduleText, regionCanonicalName);
        if (!scan.balanced()) {
            return Optional.empty();
        }

        List<TopRegion> targetRegions = scan.topRegions().stream()
                .filter(region -> region.name().equalsIgnoreCase(regionCanonicalName))
                .toList();
        if (targetRegions.size() > 1 || targetRegions.isEmpty() && scan.nestedTargetFound()) {
            return Optional.empty();
        }

        int previousStandardIndex = -1;
        for (TopRegion region : scan.topRegions()) {
            if (region.standardIndex() != null) {
                if (region.standardIndex().intValue() <= previousStandardIndex) {
                    return Optional.empty();
                }
                previousStandardIndex = region.standardIndex().intValue();
            }
        }

        String eol = moduleText.contains("\r\n") ? "\r\n" : "\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String stub = normalizeLineEndings(stubProcedureText, eol);
        if (!targetRegions.isEmpty()) {
            int offset = targetRegions.get(0).closeLineStart();
            return Optional.of(new Insertion(offset, surround(moduleText, offset, stub, eol)));
        }

        String region = BslKeywords.regionDirective(variant) + " " + regionCanonicalName //$NON-NLS-1$
                + eol + eol + stub + eol + eol + BslKeywords.endRegionDirective(variant);
        int offset = newRegionOffset(moduleText, scan.topRegions(), stdIndex);
        return Optional.of(new Insertion(offset, surround(moduleText, offset, region, eol)));
    }

    private static int newRegionOffset(String moduleText, List<TopRegion> regions, int stdIndex) {
        for (TopRegion region : regions) {
            if (region.standardIndex() != null && region.standardIndex().intValue() > stdIndex) {
                return region.openLineStart();
            }
        }
        for (int index = regions.size() - 1; index >= 0; index--) {
            TopRegion region = regions.get(index);
            if (region.standardIndex() != null && region.standardIndex().intValue() < stdIndex) {
                return region.afterCloseLine();
            }
        }
        return regions.isEmpty() ? moduleText.length() : regions.get(0).openLineStart();
    }

    private static String surround(String moduleText, int offset, String core, String eol) {
        StringBuilder insertion = new StringBuilder();
        if (offset > 0) {
            int precedingBreaks = countPrecedingLineBreaks(moduleText, offset, 2);
            insertion.append(eol.repeat(Math.max(0, 2 - precedingBreaks)));
        }
        insertion.append(core);
        if (offset == moduleText.length()) {
            insertion.append(eol);
        } else {
            int followingBreaks = countFollowingLineBreaks(moduleText, offset, 2);
            insertion.append(eol.repeat(Math.max(0, 2 - followingBreaks)));
        }
        return insertion.toString();
    }

    private static int countPrecedingLineBreaks(String text, int offset, int limit) {
        int count = 0;
        int cursor = offset;
        while (cursor > 0 && count < limit) {
            char last = text.charAt(cursor - 1);
            if (last == '\n') {
                cursor--;
                if (cursor > 0 && text.charAt(cursor - 1) == '\r') {
                    cursor--;
                }
                count++;
            } else if (last == '\r') {
                cursor--;
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    private static int countFollowingLineBreaks(String text, int offset, int limit) {
        int count = 0;
        int cursor = offset;
        while (cursor < text.length() && count < limit) {
            char first = text.charAt(cursor);
            if (first == '\r') {
                cursor++;
                if (cursor < text.length() && text.charAt(cursor) == '\n') {
                    cursor++;
                }
                count++;
            } else if (first == '\n') {
                cursor++;
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    private static String normalizeLineEndings(String text, String eol) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n'); //$NON-NLS-1$ //$NON-NLS-2$
        while (normalized.endsWith("\n")) { //$NON-NLS-1$
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return "\n".equals(eol) ? normalized : normalized.replace("\n", eol); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static ScanResult scan(String moduleText, String targetName) {
        List<TopRegion> regions = new ArrayList<>();
        TopRegionBuilder currentTop = null;
        boolean nestedTargetFound = false;
        int depth = 0;
        int lineStart = 0;
        while (lineStart < moduleText.length()) {
            int contentEnd = lineStart;
            while (contentEnd < moduleText.length()
                    && moduleText.charAt(contentEnd) != '\r'
                    && moduleText.charAt(contentEnd) != '\n') {
                contentEnd++;
            }
            int nextLineStart = contentEnd;
            if (nextLineStart < moduleText.length() && moduleText.charAt(nextLineStart) == '\r') {
                nextLineStart++;
            }
            if (nextLineStart < moduleText.length() && moduleText.charAt(nextLineStart) == '\n') {
                nextLineStart++;
            }

            String line = moduleText.substring(lineStart, contentEnd);
            Matcher open = OPEN_REGION.matcher(line);
            if (open.matches()) {
                String name = open.group(1).trim();
                if (depth == 0) {
                    currentTop = new TopRegionBuilder(lineStart, name, standardIndex(name));
                } else if (name.equalsIgnoreCase(targetName)) {
                    nestedTargetFound = true;
                }
                depth++;
            } else if (END_REGION.matcher(line).matches()) {
                if (depth == 0) {
                    return new ScanResult(List.of(), false, nestedTargetFound);
                }
                depth--;
                if (depth == 0) {
                    regions.add(currentTop.close(lineStart, nextLineStart));
                    currentTop = null;
                }
            }
            lineStart = nextLineStart;
        }
        return new ScanResult(regions, depth == 0, nestedTargetFound);
    }

    private static Integer standardIndex(String name) {
        for (FormModuleRegion region : FormModuleRegion.values()) {
            String russian = region.name(ScriptVariant.RUSSIAN);
            String english = region.name(ScriptVariant.ENGLISH);
            if (region.isSuffixed()) {
                if (hasSuffix(name, russian) || hasSuffix(name, english)) {
                    return Integer.valueOf(region.ordinalIndex());
                }
            } else if (name.equalsIgnoreCase(russian) || name.equalsIgnoreCase(english)) {
                return Integer.valueOf(region.ordinalIndex());
            }
        }
        return null;
    }

    private static boolean hasSuffix(String name, String prefix) {
        return name.length() > prefix.length() && name.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    public record Insertion(int offset, String text) {
        public Insertion {
            if (offset < 0) {
                throw new IllegalArgumentException("offset must be non-negative"); //$NON-NLS-1$
            }
            Objects.requireNonNull(text, "text"); //$NON-NLS-1$
        }
    }

    private record TopRegion(
            int openLineStart,
            String name,
            Integer standardIndex,
            int closeLineStart,
            int afterCloseLine) {
    }

    private record ScanResult(List<TopRegion> topRegions, boolean balanced, boolean nestedTargetFound) {
    }

    private record TopRegionBuilder(int openLineStart, String name, Integer standardIndex) {
        private TopRegion close(int closeLineStart, int afterCloseLine) {
            return new TopRegion(openLineStart, name, standardIndex, closeLineStart, afterCloseLine);
        }
    }
}
