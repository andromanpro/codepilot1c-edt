package com.codepilot1c.core.java.probe;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Redacts generated paths and maps javac line numbers back to the snippet. */
public final class DiagnosticsMapper {

    private DiagnosticsMapper() {
    }

    public static MappedDiagnostics map(String raw, Path sourceFile, Path tempRoot,
            int preludeLines) {
        String input = raw == null ? "" : raw; //$NON-NLS-1$
        String normalizedSource = sourceFile.toAbsolutePath().normalize().toString();
        String normalizedTempRoot = tempRoot.toAbsolutePath().normalize().toString();
        Pattern location = Pattern.compile(Pattern.quote(normalizedSource)
                + ":(\\d+):"); //$NON-NLS-1$
        Matcher matcher = location.matcher(input);
        StringBuffer mapped = new StringBuffer();
        int wrapperErrors = 0;
        while (matcher.find()) {
            int line = Integer.parseInt(matcher.group(1)) - preludeLines;
            if (line <= 0) {
                line = 0;
                wrapperErrors++;
            }
            matcher.appendReplacement(mapped, Matcher.quoteReplacement("snippet:" + line + ":")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        matcher.appendTail(mapped);

        String text = mapped.toString()
                .replace(normalizedSource, "snippet") //$NON-NLS-1$
                .replace(normalizedTempRoot, "snippet-temp") //$NON-NLS-1$
                .replace(sourceFile.getFileName().toString(), "snippet"); //$NON-NLS-1$
        return new MappedDiagnostics(
                text,
                count(text, ": error:"), //$NON-NLS-1$
                count(text, ": warning:"), //$NON-NLS-1$
                wrapperErrors);
    }

    private static int count(String text, String marker) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(marker, offset)) >= 0) {
            count++;
            offset += marker.length();
        }
        return count;
    }

    public record MappedDiagnostics(String text, int errorCount, int warningCount, int wrapperErrorCount) {
    }
}
