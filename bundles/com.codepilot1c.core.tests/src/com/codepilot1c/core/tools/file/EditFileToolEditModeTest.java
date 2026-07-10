package com.codepilot1c.core.tools.file;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.codepilot1c.core.tools.file.EditFileTool.EditMode;

/**
 * Targeted-edit parameters must take precedence over 'content': models often
 * emit a stray (frequently empty) content field alongside old_text/new_text,
 * and treating it as a full replacement wiped files.
 */
public class EditFileToolEditModeTest {

    @Test
    public void emptyContentAlongsideOldNewPairResolvesToFuzzyReplace() {
        // The reported data-loss case: partial edit + stray empty content.
        assertEquals(EditMode.FUZZY_REPLACE,
                EditFileTool.resolveEditMode("", null, "старый текст", "новый текст")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void nonBlankContentAlongsideOldNewPairResolvesToFuzzyReplace() {
        assertEquals(EditMode.FUZZY_REPLACE,
                EditFileTool.resolveEditMode("полное содержимое", null, "старый", "новый")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void editsTakePrecedenceOverContentAndPair() {
        assertEquals(EditMode.SEARCH_REPLACE_BLOCKS,
                EditFileTool.resolveEditMode("", "<<<<<<< SEARCH\na\n=======\nb\n>>>>>>> REPLACE", "a", "b")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void nonBlankContentAloneResolvesToReplaceContent() {
        assertEquals(EditMode.REPLACE_CONTENT,
                EditFileTool.resolveEditMode("Процедура Тест()\nКонецПроцедуры", null, null, null)); //$NON-NLS-1$
    }

    @Test
    public void emptyContentAloneIsRejectedMode() {
        assertEquals(EditMode.EMPTY_CONTENT_ONLY,
                EditFileTool.resolveEditMode("", null, null, null)); //$NON-NLS-1$
        assertEquals(EditMode.EMPTY_CONTENT_ONLY,
                EditFileTool.resolveEditMode("   \n", null, null, null)); //$NON-NLS-1$
    }

    @Test
    public void incompleteOldNewPairFallsBackToContent() {
        assertEquals(EditMode.REPLACE_CONTENT,
                EditFileTool.resolveEditMode("содержимое", null, "только старый", null)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void noParametersResolvesToNone() {
        assertEquals(EditMode.NONE, EditFileTool.resolveEditMode(null, null, null, null));
        assertEquals(EditMode.NONE, EditFileTool.resolveEditMode(null, null, "только старый", null)); //$NON-NLS-1$
    }
}
