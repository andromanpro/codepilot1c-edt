package com.codepilot1c.core.agent.gsd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.Test;

import com.codepilot1c.core.agent.gsd.PlanArtifact.Checkpoint;
import com.codepilot1c.core.agent.gsd.PlanArtifact.Decision;
import com.codepilot1c.core.agent.gsd.PlanArtifact.PlanTask;
import com.codepilot1c.core.agent.gsd.PlanArtifact.TaskStatus;
import com.codepilot1c.core.agent.gsd.PlanArtifact.VerificationItem;
import com.codepilot1c.core.agent.gsd.PlanArtifact.VerifyResult;

public class PlanArtifactStoreTest {

    @Test
    public void savesAndLoadsArtifactRoundTrip() throws IOException {
        Path dir = Files.createTempDirectory("cp1c-planning"); //$NON-NLS-1$
        PlanArtifactStore store = new PlanArtifactStore(dir);

        PlanArtifact artifact = new PlanArtifact("sess-1", "/ДО", "Создать справочник Товары и форму списка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        artifact.setPhase(TaskPhase.EXECUTE);
        artifact.getDecisions().add(new Decision("Тип формы?", "Форма списка")); //$NON-NLS-1$ //$NON-NLS-2$

        PlanTask task = new PlanTask("t1", "Создать справочник Товары"); //$NON-NLS-1$ //$NON-NLS-2$
        task.getFiles().add("Catalogs/Товары.mdo"); //$NON-NLS-1$
        task.getAcceptance().add("Справочник существует и без ошибок диагностики"); //$NON-NLS-1$
        task.setStatus(TaskStatus.DONE);
        task.setWave(1);
        artifact.getTasks().add(task);

        VerificationItem v = new VerificationItem("Справочник создан", "get_diagnostics scope=file"); //$NON-NLS-1$ //$NON-NLS-2$
        v.setResult(VerifyResult.PASS);
        v.setEvidence("0 errors"); //$NON-NLS-1$
        artifact.getVerification().add(v);

        artifact.setCheckpoint(new Checkpoint("t1", "Перейти к форме")); //$NON-NLS-1$ //$NON-NLS-2$

        store.save(artifact);
        assertTrue(store.exists("sess-1")); //$NON-NLS-1$

        Optional<PlanArtifact> loadedOpt = store.load("sess-1"); //$NON-NLS-1$
        assertTrue(loadedOpt.isPresent());
        PlanArtifact loaded = loadedOpt.get();

        assertEquals("sess-1", loaded.getSessionId()); //$NON-NLS-1$
        assertEquals("/ДО", loaded.getProjectPath()); //$NON-NLS-1$
        assertEquals("Создать справочник Товары и форму списка", loaded.getGoal()); //$NON-NLS-1$
        assertEquals(TaskPhase.EXECUTE, loaded.getPhase());
        assertNotNull(loaded.getCreatedAt());
        assertNotNull(loaded.getUpdatedAt());

        assertEquals(1, loaded.getDecisions().size());
        assertEquals("Форма списка", loaded.getDecisions().get(0).getAnswer()); //$NON-NLS-1$
        assertTrue(loaded.getDecisions().get(0).isLocked());

        assertEquals(1, loaded.getTasks().size());
        PlanTask lt = loaded.getTasks().get(0);
        assertEquals("t1", lt.getId()); //$NON-NLS-1$
        assertEquals(TaskStatus.DONE, lt.getStatus());
        assertEquals(1, lt.getWave());
        assertEquals(1, lt.getFiles().size());
        assertEquals(1, lt.getAcceptance().size());

        assertEquals(1, loaded.getVerification().size());
        assertEquals(VerifyResult.PASS, loaded.getVerification().get(0).getResult());

        assertNotNull(loaded.getCheckpoint());
        assertEquals("t1", loaded.getCheckpoint().getLastCompletedTaskId()); //$NON-NLS-1$
        assertNotNull(loaded.getCheckpoint().getTimestamp());
    }

    @Test
    public void loadMissingReturnsEmpty() throws IOException {
        Path dir = Files.createTempDirectory("cp1c-planning"); //$NON-NLS-1$
        PlanArtifactStore store = new PlanArtifactStore(dir);
        assertFalse(store.exists("nope")); //$NON-NLS-1$
        assertFalse(store.load("nope").isPresent()); //$NON-NLS-1$
    }

    @Test
    public void deleteRemovesArtifact() throws IOException {
        Path dir = Files.createTempDirectory("cp1c-planning"); //$NON-NLS-1$
        PlanArtifactStore store = new PlanArtifactStore(dir);
        store.save(new PlanArtifact("sess-2", "/p", "цель")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(store.delete("sess-2")); //$NON-NLS-1$
        assertFalse(store.exists("sess-2")); //$NON-NLS-1$
        assertFalse(store.delete("sess-2")); //$NON-NLS-1$
    }

    @Test
    public void defaultPhaseIsDiscuss() {
        assertEquals(TaskPhase.DISCUSS, new PlanArtifact("s", "/p", "g").getPhase()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
