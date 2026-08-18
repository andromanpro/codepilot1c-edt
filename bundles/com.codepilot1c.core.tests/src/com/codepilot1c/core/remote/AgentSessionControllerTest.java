package com.codepilot1c.core.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.codepilot1c.core.agent.profiles.InitAgentProfile;
import com.codepilot1c.core.agent.events.IAgentEventListener;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.provider.LlmProviderRegistry;

public class AgentSessionControllerTest {

    private AgentSessionController controller;
    private String cleanupClientId;
    private LlmProviderRegistry previousRegistry;

    @Before
    public void setUp() {
        controller = AgentSessionController.getInstance();
        cleanupClientId = "test-cleanup-" + UUID.randomUUID(); //$NON-NLS-1$
        controller.claimControllerLease(cleanupClientId, true);
        controller.releaseControllerLease(cleanupClientId);
        controller.resetSession("test_setup"); //$NON-NLS-1$
    }

    @After
    public void tearDown() throws Exception {
        controller.claimControllerLease(cleanupClientId, true);
        controller.releaseControllerLease(cleanupClientId);
        controller.resetSession("test_teardown"); //$NON-NLS-1$
        if (previousRegistry != null) {
            installRegistry(previousRegistry);
            previousRegistry = null;
        }
    }

    @Test
    public void controllerLeaseSupportsClaimConflictForceTakeoverAndRelease() {
        String clientA = "client-a-" + UUID.randomUUID(); //$NON-NLS-1$
        String clientB = "client-b-" + UUID.randomUUID(); //$NON-NLS-1$

        RemoteCommandResult firstClaim = controller.claimControllerLease(clientA, false);
        assertTrue(firstClaim.isOk());
        assertEquals(clientA, controller.getControllerClientId());
        assertTrue(controller.hasControllerLease(clientA));

        RemoteCommandResult conflictingClaim = controller.claimControllerLease(clientB, false);
        assertFalse(conflictingClaim.isOk());
        assertEquals("lease_conflict", conflictingClaim.getCode()); //$NON-NLS-1$
        assertEquals(clientA, conflictingClaim.getPayload().get("controllerClientId")); //$NON-NLS-1$

        RemoteCommandResult takeover = controller.claimControllerLease(clientB, true);
        assertTrue(takeover.isOk());
        assertEquals(clientB, controller.getControllerClientId());
        assertTrue(controller.hasControllerLease(clientB));
        assertFalse(controller.hasControllerLease(clientA));

        RemoteCommandResult release = controller.releaseControllerLease(clientB);
        assertTrue(release.isOk());
        assertEquals(null, controller.getControllerClientId());
    }

    @Test
    public void remoteEventsRemainMonotonicAndReplayFromSequence() {
        long baseline = controller.getEventsAfter(0).stream()
                .mapToLong(RemoteEvent::getSequence)
                .max()
                .orElse(0L);

        List<RemoteEvent> observed = new CopyOnWriteArrayList<>();
        AgentSessionController.RemoteEventListener listener = observed::add;
        controller.addRemoteEventListener(listener, baseline);

        String clientId = "client-events-" + UUID.randomUUID(); //$NON-NLS-1$
        controller.claimControllerLease(clientId, false);
        controller.releaseControllerLease(clientId);
        controller.resetSession("test_event_replay"); //$NON-NLS-1$
        controller.removeRemoteEventListener(listener);

        assertTrue(observed.size() >= 3);

        long previous = baseline;
        boolean sawLease = false;
        boolean sawReset = false;
        for (RemoteEvent event : observed) {
            assertTrue(event.getSequence() > previous);
            previous = event.getSequence();
            if ("lease_changed".equals(event.getType())) { //$NON-NLS-1$
                sawLease = true;
            }
            if ("session_reset".equals(event.getType())) { //$NON-NLS-1$
                sawReset = true;
            }
        }

        assertTrue(sawLease);
        assertTrue(sawReset);

        List<RemoteEvent> replayed = controller.getEventsAfter(baseline);
        assertFalse(replayed.isEmpty());
        assertEquals(observed.get(0).getSequence(), replayed.get(0).getSequence());
        assertEquals(observed.get(observed.size() - 1).getSequence(), replayed.get(replayed.size() - 1).getSequence());
    }

    @Test
    public void workbenchCommandsAreRejectedBeforeConfirmationWhenMissingOrDenied() {
        String clientId = "client-command-" + UUID.randomUUID(); //$NON-NLS-1$
        controller.claimControllerLease(clientId, false);
        controller.resetSession("test_command_validation"); //$NON-NLS-1$

        long baseline = controller.getEventsAfter(0).stream()
                .mapToLong(RemoteEvent::getSequence)
                .max()
                .orElse(0L);

        RemoteCommandResult missing = controller.executeWorkbenchCommand(clientId, "", Map.of()); //$NON-NLS-1$
        assertFalse(missing.isOk());
        assertEquals("missing_command", missing.getCode()); //$NON-NLS-1$
        assertTrue(controller.currentPendingConfirmation().isEmpty());

        RemoteCommandResult denied = controller.executeWorkbenchCommand(clientId, "org.eclipse.ui.file.exit", Map.of()); //$NON-NLS-1$
        assertFalse(denied.isOk());
        assertEquals("command_denied", denied.getCode()); //$NON-NLS-1$
        assertTrue(controller.currentPendingConfirmation().isEmpty());

        List<RemoteEvent> emitted = controller.getEventsAfter(baseline);
        assertTrue(emitted.stream().noneMatch(event -> "confirmation_required".equals(event.getType()))); //$NON-NLS-1$
    }

    @Test
    public void freshDesktopSubmitResetsHistoryAndStoresRequestedProfileBeforeLaunch() throws Exception {
        previousRegistry = installRegistry(emptyInitializedRegistry());
        setControllerField("conversationHistory", new ArrayList<>(List.of(LlmMessage.user("old chat")))); //$NON-NLS-1$ //$NON-NLS-2$
        String beforeSessionId = controller.getSessionId();
        long baseline = controller.getEventsAfter(0).stream()
                .mapToLong(RemoteEvent::getSequence)
                .max()
                .orElse(0L);

        controller.submitFromDesktopFresh("refresh project memory", InitAgentProfile.ID); //$NON-NLS-1$

        RemoteBootstrapResponse bootstrap = controller.buildBootstrap(
                "test-client", IdeSnapshot.unavailable("test")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotEquals(beforeSessionId, bootstrap.getSessionId());
        assertEquals(InitAgentProfile.ID, bootstrap.getAgent().get("profileId")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(0), bootstrap.getAgent().get("historySize")); //$NON-NLS-1$
        assertTrue(controller.getEventsAfter(baseline).stream()
                .anyMatch(event -> "session_reset".equals(event.getType()) //$NON-NLS-1$
                        && "desktop_fresh".equals(event.getPayload().get("reason")))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void forwardingListenerHandlesConfirmations() throws Exception {
        Field field = AgentSessionController.class.getDeclaredField("forwardingListener"); //$NON-NLS-1$
        field.setAccessible(true);
        IAgentEventListener listener = (IAgentEventListener) field.get(controller);

        assertTrue(listener.handlesConfirmations());
    }

    private void setControllerField(String name, Object value) throws Exception {
        Field field = AgentSessionController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(controller, value);
    }

    private static LlmProviderRegistry emptyInitializedRegistry() throws Exception {
        Constructor<LlmProviderRegistry> constructor = LlmProviderRegistry.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        LlmProviderRegistry registry = constructor.newInstance();

        Field initializedField = LlmProviderRegistry.class.getDeclaredField("initialized"); //$NON-NLS-1$
        initializedField.setAccessible(true);
        initializedField.set(registry, true);
        return registry;
    }

    private static LlmProviderRegistry installRegistry(LlmProviderRegistry registry) throws Exception {
        Field instanceField = LlmProviderRegistry.class.getDeclaredField("instance"); //$NON-NLS-1$
        instanceField.setAccessible(true);
        LlmProviderRegistry previous = (LlmProviderRegistry) instanceField.get(null);
        instanceField.set(null, registry);
        return previous;
    }
}
