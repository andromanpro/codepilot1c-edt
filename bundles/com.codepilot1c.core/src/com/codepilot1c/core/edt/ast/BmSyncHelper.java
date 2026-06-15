/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.ast;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Flushes EDT derived-data (BM) after a programmatic file write.
 *
 * <p>Problem this solves: {@code write_file}/{@code edit_file} write {@code .bsl}
 * content through {@link IFile#setContents}, which is correct at the workspace
 * level, but EDT recomputes its in-memory Business Model (BM) and other derived
 * data <em>asynchronously</em> in background jobs. If the tool returns immediately
 * after {@code setContents}/{@code refreshLocal}, a subsequent BM-backed read
 * (e.g. {@code bsl_get_method_body}) or {@code update_infobase} sees stale content
 * until EDT is restarted (a restart forces a full reparse from disk).</p>
 *
 * <p>Fix: after the write, block on
 * {@link IDerivedDataManager#waitImportantDataComputations(long)} for the affected
 * project so the BM is consistent before the tool returns. This mirrors the
 * existing pattern in {@code EdtTraceExportTool#flushDerivedData}.</p>
 *
 * <p>Best-effort: any failure (service not yet available, non-EDT project,
 * interruption) is swallowed/logged and never propagated — the file is already
 * written, so a sync hiccup must not turn a successful write into a failure.</p>
 */
public final class BmSyncHelper {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(BmSyncHelper.class);

    /** Upper bound for the BM recompute wait. Normal single-module recompute is sub-second. */
    public static final long DEFAULT_WAIT_MS = 30_000L;

    private BmSyncHelper() {
    }

    /**
     * Waits for EDT derived-data (BM) recomputation triggered by a write to {@code file},
     * using {@link #DEFAULT_WAIT_MS}.
     *
     * @param file the file that was just written (may be {@code null})
     * @return {@code true} if derived data finished computing (or there was nothing to wait
     *         for); {@code false} if the wait timed out or was skipped
     */
    public static boolean flushAfterWrite(IFile file) {
        return flushAfterWrite(file, DEFAULT_WAIT_MS);
    }

    /**
     * Waits for EDT derived-data (BM) recomputation triggered by a write to {@code file}.
     *
     * @param file   the file that was just written (may be {@code null})
     * @param waitMs maximum time to wait, in milliseconds
     * @return {@code true} if derived data finished computing (or there was nothing to wait
     *         for); {@code false} if the wait timed out or was skipped
     */
    public static boolean flushAfterWrite(IFile file, long waitMs) {
        if (file == null) {
            return false;
        }
        try {
            IProject project = file.getProject();
            if (project == null || !project.isOpen()) {
                return false;
            }

            EdtServiceGateway gateway = new EdtServiceGateway();
            IDtProjectManager projectManager = gateway.getDtProjectManager();
            IDtProject dtProject = projectManager.getDtProject(project);
            if (dtProject == null) {
                // Not an EDT project (or not yet imported) — nothing to flush.
                return true;
            }

            IDerivedDataManagerProvider provider = gateway.getDerivedDataManagerProvider();
            IDerivedDataManager ddManager = provider.get(dtProject);
            if (ddManager == null) {
                LOG.debug("BmSyncHelper: derived-data manager unavailable for %s", //$NON-NLS-1$
                        file.getFullPath());
                return false;
            }

            boolean done = ddManager.waitImportantDataComputations(waitMs);
            if (!done) {
                LOG.warn("BmSyncHelper: derived-data wait timed out after %d ms for %s; BM may lag until next recompute", //$NON-NLS-1$
                        waitMs, file.getFullPath());
            } else {
                LOG.debug("BmSyncHelper: BM synced after write to %s", file.getFullPath()); //$NON-NLS-1$
            }
            return done;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            LOG.debug("BmSyncHelper: interrupted while waiting for BM sync of %s", //$NON-NLS-1$
                    file.getFullPath());
            return false;
        } catch (RuntimeException e) {
            // EdtServiceGateway throws EdtAstException (unchecked) when EDT services are not
            // yet available; treat as best-effort and never fail the originating write.
            LOG.debug("BmSyncHelper: flush skipped for %s: %s", //$NON-NLS-1$
                    file.getFullPath(), e.getMessage());
            return false;
        }
    }
}
