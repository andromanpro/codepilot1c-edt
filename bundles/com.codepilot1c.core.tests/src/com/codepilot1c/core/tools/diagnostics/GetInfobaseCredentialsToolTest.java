package com.codepilot1c.core.tools.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService.AccessSettings;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class GetInfobaseCredentialsToolTest {

    @Test
    public void infobasePasswordIsNeverReturnedAndAdditionalParameterIsMasked() {
        ToolResult result = execute(AccessSettings.infobaseAuthentication(
                "Администратор", "s3cret", "/L ru /P s3cret")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(result.isSuccess());
        JsonObject payload = successJson(result);
        JsonObject data = payload.getAsJsonObject("data"); //$NON-NLS-1$
        assertFalse(data.has("password")); //$NON-NLS-1$
        assertFalse(payload.toString().contains("s3cret")); //$NON-NLS-1$
        assertTrue(data.get("password_available").getAsBoolean()); //$NON-NLS-1$
        assertEquals("unavailable", data.get("password_delivery").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("plaintext_delivery_disabled", //$NON-NLS-1$
                data.get("password_delivery_reason").getAsString()); //$NON-NLS-1$
        assertEquals("ask_user", data.get("login_strategy").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("/L ru /P <redacted>", data.get("additional_parameters").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(data.get("additional_parameters_masked").getAsBoolean()); //$NON-NLS-1$
        assertFalse(data.get("next_action").getAsString().isBlank()); //$NON-NLS-1$
        assertTrue(data.get("next_action").getAsString().contains("Never ask")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void connectionStringPasswordsAreNeverReturned() {
        String additional = "/IBConnectionString\"Srvr=srv;Pwd=connection-secret;\" /L ru"; //$NON-NLS-1$
        JsonObject data = successJson(execute(AccessSettings.additionalParameters(additional)))
                .getAsJsonObject("data"); //$NON-NLS-1$

        assertFalse(data.toString().contains("connection-secret")); //$NON-NLS-1$
        assertEquals("/IBConnectionString\"<redacted>\" /L ru", //$NON-NLS-1$
                data.get("additional_parameters").getAsString()); //$NON-NLS-1$
        assertTrue(data.get("additional_parameters_masked").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void osAuthenticationUsesOsSessionWithoutPasswordAvailability() {
        JsonObject data = successJson(execute(AccessSettings.osAuthentication("/L ru"))) //$NON-NLS-1$
                .getAsJsonObject("data"); //$NON-NLS-1$

        assertFalse(data.get("password_available").getAsBoolean()); //$NON-NLS-1$
        assertEquals("os_session", data.get("login_strategy").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(data.has("note")); //$NON-NLS-1$
        assertFalse(data.get("additional_parameters_masked").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void emptyInfobasePasswordRequiresNoPassword() {
        JsonObject data = successJson(execute(AccessSettings.infobaseAuthentication(
                "Администратор", "", ""))).getAsJsonObject("data"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertFalse(data.get("password_available").getAsBoolean()); //$NON-NLS-1$
        assertEquals("no_password_required", data.get("login_strategy").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(data.has("hint")); //$NON-NLS-1$
    }

    @Test
    public void missingSettingsPreservesStructuredErrorEnvelope() {
        ToolResult result = execute(null);

        assertFalse(result.isSuccess());
        JsonObject payload = JsonParser.parseString(result.getErrorMessage()).getAsJsonObject();
        assertEquals("error", payload.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("get_infobase_credentials", payload.get("tool").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("CREDENTIALS_NOT_DEFINED", payload.get("error_code").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(payload.has("op_id")); //$NON-NLS-1$
        assertTrue(payload.has("message")); //$NON-NLS-1$
        assertTrue(payload.has("recoverable")); //$NON-NLS-1$
        assertTrue(payload.has("details")); //$NON-NLS-1$
    }

    @Test
    public void dataIsDeterministicForIdenticalSettings() {
        AccessSettings settings = AccessSettings.infobaseAuthentication(
                "Администратор", "s3cret", "/L ru /P s3cret"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        JsonObject first = successJson(execute(settings)).getAsJsonObject("data"); //$NON-NLS-1$
        JsonObject second = successJson(execute(settings)).getAsJsonObject("data"); //$NON-NLS-1$

        assertEquals(first, second);
    }

    @Test
    public void payloadBuilderAcceptsAvailabilityOnlyInsteadOfPassword() throws Exception {
        Method method = GetInfobaseCredentialsTool.class.getDeclaredMethod("fillCredentialsData", //$NON-NLS-1$
                JsonObject.class, String.class, String.class, String.class, boolean.class, String.class);

        assertNotNull(method);
        assertEquals(boolean.class, method.getParameterTypes()[4]);
    }

    @Test
    public void additionalAuthenticationMetadataIsConsistentWhenPasswordIsAvailable() {
        JsonObject data = new JsonObject();

        GetInfobaseCredentialsTool.fillCredentialsData(
                data, "MyProject", "additional", "Admin", true, ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertTrue(data.get("password_available").getAsBoolean()); //$NON-NLS-1$
        assertEquals("ask_user", data.get("login_strategy").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(data.has("hint")); //$NON-NLS-1$
    }

    private static ToolResult execute(AccessSettings settings) {
        return new GetInfobaseCredentialsTool(new FakeRuntimeService(settings))
                .execute(Map.of("projectName", "MyProject")) //$NON-NLS-1$ //$NON-NLS-2$
                .join();
    }

    private static JsonObject successJson(ToolResult result) {
        assertTrue(result.isSuccess());
        return JsonParser.parseString(result.getContent()).getAsJsonObject();
    }

    private static final class FakeRuntimeService extends EdtRuntimeService {

        private final AccessSettings settings;

        private FakeRuntimeService(AccessSettings settings) {
            this.settings = settings;
        }

        @Override
        public AccessSettings resolveAccessSettings(String projectName) {
            return settings;
        }
    }
}
