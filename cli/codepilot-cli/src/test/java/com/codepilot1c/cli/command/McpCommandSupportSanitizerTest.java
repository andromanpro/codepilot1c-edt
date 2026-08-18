/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import com.codepilot1c.cli.render.JsonWriter;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Locks down the credential-key allowlist without broadly stripping ordinary tool payloads. */
@RunWith(Parameterized.class)
public class McpCommandSupportSanitizerTest {
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<Object[]> credentialKeys() {
        return List.of(
                new Object[] { "API_KEY" }, new Object[] { "api-secret" },
                new Object[] { "consumerSecret" }, new Object[] { "client_secret" },
                new Object[] { "secretKey" }, new Object[] { "private-key" },
                new Object[] { "password" }, new Object[] { "pass_phrase" },
                new Object[] { "authToken" }, new Object[] { "access_token" },
                new Object[] { "refresh-token" }, new Object[] { "idToken" },
                new Object[] { "authorization" }, new Object[] { "credentials" });
    }

    private final String key;

    public McpCommandSupportSanitizerTest(String key) { this.key = key; }

    @Test public void recognizesCanonicalCredentialKey() {
        assertTrue(McpCommandSupport.isSensitiveCredentialKey(key));
    }

    @Test public void recursivelyDropsCredentialKeysInsideObjectsAndArrays() {
        JsonObject root = new JsonObject();
        JsonObject nested = new JsonObject();
        nested.addProperty(key, "secret-value");
        JsonArray entries = new JsonArray();
        JsonObject arrayItem = new JsonObject();
        arrayItem.addProperty(key, "array-secret-value");
        entries.add(arrayItem);
        root.add("nested", nested);
        root.add("entries", entries);
        root.addProperty("monkey", "banana");
        root.addProperty("tokenCount", 7);
        root.addProperty("publicKey", "ordinary-public-key");

        String rendered = JsonWriter.write(McpCommandSupport.jsonValue(root));
        assertFalse(rendered.contains("secret-value"));
        assertFalse(rendered.contains("array-secret-value"));
        assertTrue(rendered.contains("\"monkey\":\"banana\""));
        assertTrue(rendered.contains("\"tokenCount\":7"));
        assertTrue(rendered.contains("\"publicKey\":\"ordinary-public-key\""));
    }

    @Test public void leavesOrdinaryKeysAndBearerDocumentationTextUntouched() {
        assertFalse(McpCommandSupport.isSensitiveCredentialKey("publicKey"));
        assertFalse(McpCommandSupport.isSensitiveCredentialKey("tokenCount"));
        assertFalse(McpCommandSupport.isSensitiveCredentialKey("monkey"));
        assertEquals("Bearer docs example", McpCommandSupport.safeText("Bearer docs example"));
    }

    @Test public void redactsOnlyACompletePlausibleBearerAuthorizationValue() {
        assertEquals("<redacted>", McpCommandSupport.safeText("Bearer abc.def-_"));
        assertEquals("<redacted>", McpCommandSupport.safeText("Authorization: Bearer abc.def-_"));
        assertEquals("See Bearer docs example", McpCommandSupport.safeText("See Bearer docs example"));
    }
}
