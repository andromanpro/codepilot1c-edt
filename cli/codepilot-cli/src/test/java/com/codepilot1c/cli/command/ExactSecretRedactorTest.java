/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.cli.render.JsonWriter;

public class ExactSecretRedactorTest {
    @Test public void redactsExactConfiguredSecretsRecursivelyAndHandlesOverlaps() {
        char[] shortSecret = "known-token".toCharArray();
        char[] longSecret = "known-token-suffix".toCharArray();
        try (ExactSecretRedactor redactor = ExactSecretRedactor.of(shortSecret, longSecret)) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("prefix-known-token-suffix-key", List.of(
                    "before known-token-suffix after", "known-token", "password=hunter2"));
            String rendered = JsonWriter.write(redactor.redact(value));
            assertFalse(rendered.contains("known-token"));
            assertTrue(rendered.contains("prefix-<redacted>-key"));
            assertTrue(rendered.contains("before <redacted> after"));
            assertTrue(rendered.contains("password=hunter2"));
        }
    }

    @Test public void unknownCredentialLikeTextIsNotHeuristicallyRedacted() {
        try (ExactSecretRedactor redactor = ExactSecretRedactor.of("different-secret".toCharArray())) {
            assertEquals("apiKey=unknown password=hunter2 Authorization: Bearer docs-example",
                    redactor.redact("apiKey=unknown password=hunter2 Authorization: Bearer docs-example"));
        }
    }

    @Test public void closeWipesPrivateSecretCopies() throws Exception {
        ExactSecretRedactor redactor = ExactSecretRedactor.of("wipe-me".toCharArray());
        Field field = ExactSecretRedactor.class.getDeclaredField("secrets");
        field.setAccessible(true);
        char[][] copies = (char[][]) field.get(redactor);
        assertArrayEquals("wipe-me".toCharArray(), copies[0]);
        redactor.close();
        for (char character : copies[0]) assertEquals('\0', character);
        assertEquals("wipe-me", redactor.redact("wipe-me"));
    }
}
