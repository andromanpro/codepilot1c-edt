package com.codepilot1c.core.java.probe;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Builds the fixed Tier A0 javac command; only generated temporary paths vary. */
public final class JavacCommandBuilder {

    private final Path javac;

    public JavacCommandBuilder(Path javac) {
        this.javac = Objects.requireNonNull(javac, "javac").toAbsolutePath().normalize(); //$NON-NLS-1$
    }

    public List<String> build(Path sourceFile, Path outDir, Path classpathDir,
            Path processorPathDir) {
        Objects.requireNonNull(sourceFile, "sourceFile"); //$NON-NLS-1$
        Objects.requireNonNull(outDir, "outDir"); //$NON-NLS-1$
        Objects.requireNonNull(classpathDir, "classpathDir"); //$NON-NLS-1$
        Objects.requireNonNull(processorPathDir, "processorPathDir"); //$NON-NLS-1$
        return List.of(
                javac.toString(),
                "-J-Xmx256m", //$NON-NLS-1$
                "-J-Duser.language=en", //$NON-NLS-1$
                "-J-Duser.country=US", //$NON-NLS-1$
                "-J-Dfile.encoding=UTF-8", //$NON-NLS-1$
                "-J-Dstdout.encoding=UTF-8", //$NON-NLS-1$
                "-J-Dstderr.encoding=UTF-8", //$NON-NLS-1$
                "--release", "17", //$NON-NLS-1$ //$NON-NLS-2$
                "-proc:none", //$NON-NLS-1$
                "-classpath", classpathDir.toAbsolutePath().normalize().toString(), //$NON-NLS-1$
                "-sourcepath", "", //$NON-NLS-1$ //$NON-NLS-2$
                "-processorpath", processorPathDir.toAbsolutePath().normalize().toString(), //$NON-NLS-1$
                "-implicit:none", //$NON-NLS-1$
                "-nowarn", //$NON-NLS-1$
                "-Xmaxerrs", "20", //$NON-NLS-1$ //$NON-NLS-2$
                "-Xmaxwarns", "20", //$NON-NLS-1$ //$NON-NLS-2$
                "-encoding", "UTF-8", //$NON-NLS-1$ //$NON-NLS-2$
                "-d", outDir.toAbsolutePath().normalize().toString(), //$NON-NLS-1$
                sourceFile.toAbsolutePath().normalize().toString());
    }
}
