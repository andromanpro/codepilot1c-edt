package com.codepilot1c.core.provider.codex;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.model.LlmAttachment;
import com.codepilot1c.core.model.LlmContentPart;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmRequest;

/**
 * The Codex Responses builder must serialize user image attachments as {@code input_image}
 * data URIs so multimodal models (e.g. gpt-5.5) actually receive the picture instead of a
 * {@code [Image: ...]} text placeholder.
 */
public class CodexResponsesRequestBuilderImageTest {

    @Test
    public void userImageAttachmentIsSerializedAsInputImageDataUri() throws Exception {
        Path png = Files.createTempFile("clipboard-image", ".png"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.write(png, new byte[] { (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3, 4 });
        try {
            LlmAttachment image = LlmAttachment.builder()
                    .kind(LlmAttachment.Kind.IMAGE)
                    .displayName("clipboard-image.png") //$NON-NLS-1$
                    .mimeType("image/png") //$NON-NLS-1$
                    .cachePath(png.toString())
                    .build();
            LlmMessage user = new LlmMessage(LlmMessage.Role.USER,
                    List.of(LlmContentPart.text("что на скрине"), LlmContentPart.image(image))); //$NON-NLS-1$
            LlmRequest request = LlmRequest.builder().addMessage(user).build();

            String body = new CodexResponsesRequestBuilder().build(request, "gpt-5.5", 1000, false); //$NON-NLS-1$

            assertTrue("must emit input_image part", body.contains("\"input_image\"")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("must embed base64 PNG data URI", body.contains("data:image/png;base64,")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("must keep the user text", body.contains("что на скрине")); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse("must not fall back to [Image: placeholder", body.contains("[Image:")); //$NON-NLS-1$ //$NON-NLS-2$
        } finally {
            Files.deleteIfExists(png);
        }
    }

    @Test
    public void userTextOnlyMessageStillUsesInputText() {
        LlmMessage user = new LlmMessage(LlmMessage.Role.USER, "просто текст"); //$NON-NLS-1$
        LlmRequest request = LlmRequest.builder().addMessage(user).build();

        String body = new CodexResponsesRequestBuilder().build(request, "gpt-5.5", 1000, false); //$NON-NLS-1$

        assertTrue(body.contains("\"input_text\"")); //$NON-NLS-1$
        assertTrue(body.contains("просто текст")); //$NON-NLS-1$
        assertFalse(body.contains("\"input_image\"")); //$NON-NLS-1$
    }
}
