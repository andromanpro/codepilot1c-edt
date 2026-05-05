package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link ChildrenOpsValidator}.
 *
 * <p>Pins the rejection list for {@code update_metadata.children_ops} ops
 * that express "create a new child" intent.  The previous behaviour leaked
 * an indirect "child_fqn is required" / "Metadata child object not found"
 * error sequence that made the agent think the request was malformed,
 * when the real diagnosis is "wrong tool — use add_metadata_child".</p>
 */
public class ChildrenOpsValidatorTest {

    @Test
    public void normalizeOpTokenStripsUnderscoresHyphensAndSpaces() {
        assertEquals("addchild", ChildrenOpsValidator.normalizeOpToken("add_child")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("addchild", ChildrenOpsValidator.normalizeOpToken("add-child")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("addchild", ChildrenOpsValidator.normalizeOpToken("add child")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("createchild", ChildrenOpsValidator.normalizeOpToken("Create_Child")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("", ChildrenOpsValidator.normalizeOpToken(null)); //$NON-NLS-1$
        assertEquals("", ChildrenOpsValidator.normalizeOpToken("")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void recognizesAddIntentAcrossCommonAliases() {
        // The exact tokens the BF-8908 handoff used and the most plausible alternates.
        assertTrue(ChildrenOpsValidator.isCreateChildIntent("add")); //$NON-NLS-1$
        assertTrue(ChildrenOpsValidator.isCreateChildIntent("addchild")); //$NON-NLS-1$
        assertTrue(ChildrenOpsValidator.isCreateChildIntent("create")); //$NON-NLS-1$
        assertTrue(ChildrenOpsValidator.isCreateChildIntent("createchild")); //$NON-NLS-1$
        assertTrue(ChildrenOpsValidator.isCreateChildIntent("new")); //$NON-NLS-1$
        assertTrue(ChildrenOpsValidator.isCreateChildIntent("newchild")); //$NON-NLS-1$
        assertTrue(ChildrenOpsValidator.isCreateChildIntent("insert")); //$NON-NLS-1$
    }

    @Test
    public void recognizesAddIntentThroughNormalizationPipeline() {
        // Caller code does normalizeOpToken(...) first.  Verify the round trip.
        assertTrue(ChildrenOpsValidator.isCreateChildIntent(
                ChildrenOpsValidator.normalizeOpToken("add"))); //$NON-NLS-1$
        assertTrue(ChildrenOpsValidator.isCreateChildIntent(
                ChildrenOpsValidator.normalizeOpToken("Add_Child"))); //$NON-NLS-1$
        assertTrue(ChildrenOpsValidator.isCreateChildIntent(
                ChildrenOpsValidator.normalizeOpToken("CREATE-CHILD"))); //$NON-NLS-1$
        assertTrue(ChildrenOpsValidator.isCreateChildIntent(
                ChildrenOpsValidator.normalizeOpToken(" insert "))); //$NON-NLS-1$
    }

    @Test
    public void doesNotMisfireOnSupportedOps() {
        // These are the ops EdtMetadataService.applyChildOperations actually handles.
        assertFalse(ChildrenOpsValidator.isCreateChildIntent("rename")); //$NON-NLS-1$
        assertFalse(ChildrenOpsValidator.isCreateChildIntent("renamechild")); //$NON-NLS-1$
        assertFalse(ChildrenOpsValidator.isCreateChildIntent("delete")); //$NON-NLS-1$
        assertFalse(ChildrenOpsValidator.isCreateChildIntent("deletechild")); //$NON-NLS-1$
        assertFalse(ChildrenOpsValidator.isCreateChildIntent("remove")); //$NON-NLS-1$
        assertFalse(ChildrenOpsValidator.isCreateChildIntent("set")); //$NON-NLS-1$
        assertFalse(ChildrenOpsValidator.isCreateChildIntent("setchildprops")); //$NON-NLS-1$
        assertFalse(ChildrenOpsValidator.isCreateChildIntent("update")); //$NON-NLS-1$
    }

    @Test
    public void doesNotMisfireOnNullOrEmpty() {
        assertFalse(ChildrenOpsValidator.isCreateChildIntent(null));
        assertFalse(ChildrenOpsValidator.isCreateChildIntent("")); //$NON-NLS-1$
    }

    @Test
    public void rejectionMessageEchoesRawOpAndPointsToCorrectTool() {
        String message = ChildrenOpsValidator.createChildIntentRejectionMessage("Add"); //$NON-NLS-1$
        assertTrue("rejection message must echo the raw op verbatim:\n" + message, //$NON-NLS-1$
                message.contains("'Add'")); //$NON-NLS-1$
        assertTrue("rejection message must point at add_metadata_child:\n" + message, //$NON-NLS-1$
                message.contains("add_metadata_child")); //$NON-NLS-1$
        assertTrue("rejection message must mention the parent_fqn/child_kind shape:\n" + message, //$NON-NLS-1$
                message.contains("parent_fqn") && message.contains("child_kind")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
