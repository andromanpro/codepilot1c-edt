package com.codepilot1c.core.tools.file;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EditResultGuardTest {

    @Test
    public void blocksEmptyResultOverNonEmptySource() {
        assertTrue(EditResultGuard.wouldWipeNonEmptyFile("Процедура Тест()\nКонецПроцедуры", "")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void blocksBlankResultOverNonEmptySource() {
        assertTrue(EditResultGuard.wouldWipeNonEmptyFile("содержимое", "   \n\t")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void blocksNullResultOverNonEmptySource() {
        assertTrue(EditResultGuard.wouldWipeNonEmptyFile("содержимое", null)); //$NON-NLS-1$
    }

    @Test
    public void allowsEmptyResultOverEmptySource() {
        assertFalse(EditResultGuard.wouldWipeNonEmptyFile("", "")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(EditResultGuard.wouldWipeNonEmptyFile(null, "")); //$NON-NLS-1$
        assertFalse(EditResultGuard.wouldWipeNonEmptyFile("  \n", "")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void allowsNonEmptyResult() {
        assertFalse(EditResultGuard.wouldWipeNonEmptyFile("старое", "новое")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(EditResultGuard.wouldWipeNonEmptyFile("", "новое")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void rejectionMessagePointsToIntentionalPath() {
        String message = EditResultGuard.wipeRejectionMessage("/Проект/src/CommonModules/Тест/Module.bsl"); //$NON-NLS-1$
        assertTrue(message.contains("/Проект/src/CommonModules/Тест/Module.bsl")); //$NON-NLS-1$
        assertTrue(message.contains("allow_empty=true")); //$NON-NLS-1$
    }
}
