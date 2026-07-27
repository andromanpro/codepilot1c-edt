package com.codepilot1c.core.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class FileEditApplierTest {

    private final FileEditApplier applier = new FileEditApplier();

    @Test
    public void emptyBlockListReturnsUnchangedContent() {
        FileEditApplier.ApplyResult result = applier.apply("исходный текст", Collections.emptyList()); //$NON-NLS-1$
        assertTrue(result.allSuccessful());
        assertEquals("исходный текст", result.afterContent()); //$NON-NLS-1$
        assertFalse(result.hasChanges());
    }

    @Test
    public void failedMatchLeavesContentUnchangedAndReportsFeedback() {
        String content = "Процедура Тест()\n    Возврат;\nКонецПроцедуры"; //$NON-NLS-1$
        EditBlock block = new EditBlock("ТакогоТекстаНетСовсемВФайлеНикогда", "замена", 0); //$NON-NLS-1$ //$NON-NLS-2$

        FileEditApplier.ApplyResult result = applier.apply(content, List.of(block));

        assertFalse(result.allSuccessful());
        assertEquals(content, result.afterContent());
        assertFalse(result.getFailureFeedback().isEmpty());
    }

    @Test
    public void overlappingBlocksAreRejectedWithoutChanges() {
        String content = "альфа бета гамма"; //$NON-NLS-1$
        EditBlock first = new EditBlock("альфа бета", "X", 0); //$NON-NLS-1$ //$NON-NLS-2$
        EditBlock second = new EditBlock("бета гамма", "Y", 1); //$NON-NLS-1$ //$NON-NLS-2$

        FileEditApplier.ApplyResult result = applier.apply(content, List.of(first, second));

        assertFalse(result.allSuccessful());
        assertEquals(content, result.afterContent());
    }

    @Test
    public void multipleBlocksApplyBottomUpPreservingOffsets() {
        String content = "альфа\nбета\nгамма"; //$NON-NLS-1$
        EditBlock first = new EditBlock("альфа", "А1", 0); //$NON-NLS-1$ //$NON-NLS-2$
        EditBlock second = new EditBlock("гамма", "Г1", 1); //$NON-NLS-1$ //$NON-NLS-2$

        FileEditApplier.ApplyResult result = applier.apply(content, List.of(first, second));

        assertTrue(result.allSuccessful());
        assertEquals("А1\nбета\nГ1", result.afterContent()); //$NON-NLS-1$
    }

    @Test
    public void wholeFileDeletionProducesEmptyResultAtApplierLevel() {
        // The applier itself permits a whole-file deletion; the empty-result
        // guard lives in EditFileTool (EditResultGuard) — this test pins the
        // applier-level contract the tool guard compensates for.
        String content = "строка1\nстрока2"; //$NON-NLS-1$
        EditBlock block = new EditBlock(content, "", 0); //$NON-NLS-1$

        FileEditApplier.ApplyResult result = applier.apply(content, List.of(block));

        assertTrue(result.allSuccessful());
        assertTrue(result.afterContent().isEmpty());
    }
}
