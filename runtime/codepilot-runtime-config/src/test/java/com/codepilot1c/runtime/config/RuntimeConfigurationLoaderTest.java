/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;

import org.junit.Assume;
import org.junit.Test;

/** Contract tests for precedence, hostile input, and secret lifecycle. */
public class RuntimeConfigurationLoaderTest {

    @Test
    public void explicitOverridesWinOverPropertiesEnvironmentAndFile() throws Exception {
        Path config = config("provider.model=file-model\nprovider.baseUri=https://file.example/v1\nagent.maxSteps=9\n"); //$NON-NLS-1$
        try (RuntimeConfiguration configuration = RuntimeConfigurationLoader.builder()
                .configFile(config)
                .environment(Map.of("CODEPILOT_PROVIDER_MODEL", "environment-model")) //$NON-NLS-1$ //$NON-NLS-2$
                .systemProperties(Map.of("codepilot.provider.model", "property-model")) //$NON-NLS-1$ //$NON-NLS-2$
                .override(RuntimeSetting.PROVIDER_MODEL, "explicit-model") //$NON-NLS-1$
                .load()) {
            assertEquals("explicit-model", configuration.providerModel().value()); //$NON-NLS-1$
            assertEquals(ConfigurationSource.EXPLICIT, configuration.providerModel().source());
            assertEquals(ConfigurationSource.CONFIG_FILE, configuration.providerBaseUri().source());
            assertEquals(ConfigurationSource.CONFIG_FILE, configuration.agentMaxSteps().source());
            assertEquals(9, configuration.agentMaxSteps().value().intValue());
        }
    }

    @Test
    public void propertyThenEnvironmentThenFileThenDefaultsAreReportedPrecisely() throws Exception {
        Path config = config("provider.model=file-model\n"); //$NON-NLS-1$
        try (RuntimeConfiguration property = RuntimeConfigurationLoader.builder().configFile(config)
                .environment(Map.of("CODEPILOT_PROVIDER_MODEL", "environment-model")) //$NON-NLS-1$ //$NON-NLS-2$
                .systemProperties(Map.of("codepilot.provider.model", "property-model")) //$NON-NLS-1$ //$NON-NLS-2$
                .load();
                RuntimeConfiguration environment = RuntimeConfigurationLoader.builder().configFile(config)
                        .environment(Map.of("CODEPILOT_PROVIDER_MODEL", "environment-model")) //$NON-NLS-1$ //$NON-NLS-2$
                        .load();
                RuntimeConfiguration file = RuntimeConfigurationLoader.builder().configFile(config).load()) {
            assertEquals(ConfigurationSource.SYSTEM_PROPERTY, property.sourceOf(RuntimeSetting.PROVIDER_MODEL));
            assertEquals(ConfigurationSource.ENVIRONMENT, environment.sourceOf(RuntimeSetting.PROVIDER_MODEL));
            assertEquals(ConfigurationSource.CONFIG_FILE, file.sourceOf(RuntimeSetting.PROVIDER_MODEL));
            assertEquals(ConfigurationSource.DEFAULT, file.sourceOf(RuntimeSetting.AGENT_TIMEOUT_MILLIS));
            assertEquals(Duration.ofMinutes(5), file.agentTimeout().value());
        }
    }

    @Test
    public void portablePathsKeepPlatformConventions() {
        assertEquals("/xdg/codepilot/runtime.properties", //$NON-NLS-1$
                PortableConfigPath.resolve("Linux", Map.of("XDG_CONFIG_HOME", "/xdg"), "/project/user").toString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("/project/user/Library/Application Support/CodePilot/runtime.properties", //$NON-NLS-1$
                PortableConfigPath.resolve("Mac OS X", Map.of(), "/project/user").toString()); //$NON-NLS-1$ //$NON-NLS-2$
        String windows = PortableConfigPath.resolve("Windows 11", Map.of("APPDATA", "C:\\Users\\a\\AppData\\Roaming"), "C:\\Users\\a").toString(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(windows.contains("\\CodePilot\\runtime.properties")); //$NON-NLS-1$
        assertFalse(windows.contains("/CodePilot/runtime.properties")); //$NON-NLS-1$
    }

    @Test
    public void rejectsUnsafeEndpointAndInvalidBoundsWithDeterministicErrors() throws Exception {
        Path config = config(""); //$NON-NLS-1$
        expect(ConfigurationErrorCode.INVALID_ENDPOINT, RuntimeSetting.PROVIDER_BASE_URI,
                () -> load(config, RuntimeSetting.PROVIDER_BASE_URI, "http://example.test/v1")); //$NON-NLS-1$
        expect(ConfigurationErrorCode.INVALID_ENDPOINT, RuntimeSetting.PROVIDER_BASE_URI,
                () -> load(config, RuntimeSetting.PROVIDER_BASE_URI, "https://secret@example.test/v1")); //$NON-NLS-1$
        expect(ConfigurationErrorCode.INVALID_VALUE, RuntimeSetting.PROVIDER_MODEL,
                () -> load(config, RuntimeSetting.PROVIDER_MODEL, "model with spaces")); //$NON-NLS-1$
        expect(ConfigurationErrorCode.INVALID_VALUE, RuntimeSetting.PROVIDER_REQUEST_TIMEOUT_MILLIS,
                () -> load(config, RuntimeSetting.PROVIDER_REQUEST_TIMEOUT_MILLIS, "3600001")); //$NON-NLS-1$
        expect(ConfigurationErrorCode.INVALID_VALUE, RuntimeSetting.AGENT_MAX_STEPS,
                () -> load(config, RuntimeSetting.AGENT_MAX_STEPS, "129")); //$NON-NLS-1$
        try (RuntimeConfiguration ipv6 = load(config, RuntimeSetting.PROVIDER_BASE_URI, "http://[::1]:8080/v1"); //$NON-NLS-1$
                RuntimeConfiguration remoteHttps = load(config, RuntimeSetting.PROVIDER_BASE_URI, "https://provider.example/v1")) { //$NON-NLS-1$
            assertEquals("http://[::1]:8080/v1", ipv6.providerBaseUri().value().toString()); //$NON-NLS-1$
            assertEquals("https://provider.example/v1", remoteHttps.providerBaseUri().value().toString()); //$NON-NLS-1$
        }
    }

    @Test
    public void rejectsUnknownDuplicateAndOversizedConfigFiles() throws Exception {
        Path unknown = config("provider.apiKey=do-not-accept\n"); //$NON-NLS-1$
        expect(ConfigurationErrorCode.INVALID_CONFIG_FILE, "config", //$NON-NLS-1$
                () -> RuntimeConfigurationLoader.builder().configFile(unknown).load());
        Path duplicate = config("provider.model=a\nprovider.model=b\n"); //$NON-NLS-1$
        expect(ConfigurationErrorCode.INVALID_CONFIG_FILE, "config", //$NON-NLS-1$
                () -> RuntimeConfigurationLoader.builder().configFile(duplicate).load());
        Path oversized = Files.createTempFile("runtime-config-", ".properties"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(oversized, "#".repeat(StrictPropertiesFile.MAX_CONFIG_BYTES + 1), StandardCharsets.UTF_8); //$NON-NLS-1$
        expect(ConfigurationErrorCode.INVALID_CONFIG_FILE, "config", //$NON-NLS-1$
                () -> RuntimeConfigurationLoader.builder().configFile(oversized).load());
    }

    @Test
    public void acceptsLfAndCrLfButRejectsBareOrEmbeddedCrAndNulAnywhere() throws Exception {
        try (RuntimeConfiguration lf = RuntimeConfigurationLoader.builder().configFile(config("provider.model=lf-model\n")).load(); //$NON-NLS-1$
                RuntimeConfiguration crlf = RuntimeConfigurationLoader.builder().configFile(config("provider.model=crlf-model\r\n")).load()) { //$NON-NLS-1$
            assertEquals("lf-model", lf.providerModel().value()); //$NON-NLS-1$
            assertEquals("crlf-model", crlf.providerModel().value()); //$NON-NLS-1$
        }
        for (String hostile : new String[] {
                "provider.model=bare-cr\r", //$NON-NLS-1$
                "provider.model=embedded\r-cr\n", //$NON-NLS-1$
                "# comment\0\n", //$NON-NLS-1$
                "  \0\n", //$NON-NLS-1$
                "provider.model=value\0\n" //$NON-NLS-1$
        }) {
            expect(ConfigurationErrorCode.INVALID_CONFIG_FILE, "config", //$NON-NLS-1$
                    () -> RuntimeConfigurationLoader.builder().configFile(config(hostile)).load());
        }
    }

    @Test
    public void rejectsOversizedSecretBeforeReadingIt() throws Exception {
        Path secret = Files.createTempFile("runtime-secret-", ".txt"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(secret, "x".repeat(8 * 1024 + 1), StandardCharsets.UTF_8); //$NON-NLS-1$
        Path config = config("provider.apiKeyFile=" + secret + "\n"); //$NON-NLS-1$ //$NON-NLS-2$
        expect(ConfigurationErrorCode.SECRET_TOO_LARGE, RuntimeSetting.PROVIDER_API_KEY_FILE.key(),
                () -> RuntimeConfigurationLoader.builder().configFile(config).load());
    }

    @Test
    public void rejectsSymlinkedConfigAndSecretFilesWhenSupported() throws Exception {
        Path target = config("provider.model=target-model\n"); //$NON-NLS-1$
        Path link = target.resolveSibling(target.getFileName() + ".link"); //$NON-NLS-1$
        try {
            Files.deleteIfExists(link);
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException exception) {
            Assume.assumeNoException("symlinks unavailable", exception); //$NON-NLS-1$
        }
        expect(ConfigurationErrorCode.UNSAFE_CONFIG_FILE, "config", //$NON-NLS-1$
                () -> RuntimeConfigurationLoader.builder().configFile(link).load());

        Path secret = Files.createTempFile("runtime-secret-", ".txt"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(secret, "not-a-real-key", StandardCharsets.UTF_8); //$NON-NLS-1$
        Path secretLink = secret.resolveSibling(secret.getFileName() + ".link"); //$NON-NLS-1$
        Files.createSymbolicLink(secretLink, secret);
        Path config = config("provider.apiKeyFile=" + secretLink + "\n"); //$NON-NLS-1$ //$NON-NLS-2$
        expect(ConfigurationErrorCode.UNSAFE_SECRET_FILE, RuntimeSetting.PROVIDER_API_KEY_FILE.key(),
                () -> RuntimeConfigurationLoader.builder().configFile(config).load());
    }

    @Test
    public void secretUsesCopiesRedactsDiagnosticsAndWipesLifecycleState() throws Exception {
        Path secret = Files.createTempFile("runtime-secret-", ".txt"); //$NON-NLS-1$ //$NON-NLS-2$
        String value = "super-secret-value"; //$NON-NLS-1$
        Files.writeString(secret, value + "\n", StandardCharsets.UTF_8); //$NON-NLS-1$
        privatePermissionsOrSkip(secret);
        Path config = config("provider.apiKeyFile=" + secret + "\n"); //$NON-NLS-1$ //$NON-NLS-2$
        RuntimeConfiguration configuration = RuntimeConfigurationLoader.builder().configFile(config).load();
        char[] first = configuration.copyProviderApiKey();
        char[] second = configuration.copyProviderApiKey();
        assertArrayEquals(value.toCharArray(), first);
        assertArrayEquals(first, second);
        assertFalse(first == second);
        assertTrue(configuration.hasProviderApiKey());
        assertFalse(configuration.toString().contains(value));
        java.util.Arrays.fill(first, '\0');
        java.util.Arrays.fill(second, '\0');
        configuration.close();
        assertFalse(configuration.hasProviderApiKey());
        try {
            configuration.copyProviderApiKey();
            fail("closed configuration must not expose secrets"); //$NON-NLS-1$
        } catch (IllegalStateException expected) {
            assertEquals("RuntimeConfiguration is closed", expected.getMessage()); //$NON-NLS-1$
        }
    }

    @Test
    public void rejectsInlineSecretAliasesAndIntegrationSnapshotsOwnIndependentCopies() throws Exception {
        Path empty = config(""); //$NON-NLS-1$
        expect(ConfigurationErrorCode.INVALID_VALUE, "config", //$NON-NLS-1$
                () -> RuntimeConfigurationLoader.builder().configFile(empty)
                        .environment(Map.of("CODEPILOT_PROVIDER_API_KEY", "not-allowed")).load()); //$NON-NLS-1$ //$NON-NLS-2$
        expect(ConfigurationErrorCode.INVALID_VALUE, "config", //$NON-NLS-1$
                () -> RuntimeConfigurationLoader.builder().configFile(empty)
                        .systemProperties(Map.of("codepilot.provider.Token", "not-allowed")).load()); //$NON-NLS-1$ //$NON-NLS-2$
        Path disguisedSecret = config("openai.apiKey=raw-secret-value\n"); //$NON-NLS-1$
        expect(ConfigurationErrorCode.INVALID_CONFIG_FILE, "config", //$NON-NLS-1$
                () -> RuntimeConfigurationLoader.builder().configFile(disguisedSecret).load());

        Path secret = Files.createTempFile("runtime-secret-", ".txt"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(secret, "snapshot-secret", StandardCharsets.UTF_8); //$NON-NLS-1$
        privatePermissionsOrSkip(secret);
        try (RuntimeConfiguration configuration = RuntimeConfigurationLoader.builder()
                .configFile(config("provider.apiKeyFile=" + secret + "\nmcp.endpoint=http://localhost:8080/mcp\n")) //$NON-NLS-1$ //$NON-NLS-2$
                .load();
                ProviderRuntimeSettings provider = configuration.providerSettings()) {
            char[] providerKey = provider.copyApiKey();
            assertArrayEquals("snapshot-secret".toCharArray(), providerKey); //$NON-NLS-1$
            assertTrue(configuration.mcpSettings().endpoint().isPresent());
            assertEquals(16, configuration.agentSettings().maxSteps());
            configuration.close();
            assertArrayEquals("snapshot-secret".toCharArray(), provider.copyApiKey()); //$NON-NLS-1$
            java.util.Arrays.fill(providerKey, '\0');
        }
    }

    @Test
    public void rejectsTooBroadSecretPermissionsWhenPosixIsAvailable() throws Exception {
        Path secret = Files.createTempFile("runtime-secret-", ".txt"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(secret, "key", StandardCharsets.UTF_8); //$NON-NLS-1$
        try {
            Files.setPosixFilePermissions(secret, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ));
        } catch (UnsupportedOperationException exception) {
            Assume.assumeNoException("POSIX permissions unavailable", exception); //$NON-NLS-1$
        }
        Path config = config("provider.apiKeyFile=" + secret + "\n"); //$NON-NLS-1$ //$NON-NLS-2$
        expect(ConfigurationErrorCode.UNSAFE_SECRET_FILE, RuntimeSetting.PROVIDER_API_KEY_FILE.key(),
                () -> RuntimeConfigurationLoader.builder().configFile(config).load());
    }

    @Test
    public void rejectsGroupWritableConfigAndUnsafeDefaultParentWhenPosixIsAvailable() throws Exception {
        Path config = config("provider.baseUri=https://provider.example/v1\n"); //$NON-NLS-1$
        try {
            Files.setPosixFilePermissions(config, EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE));
        } catch (UnsupportedOperationException exception) {
            Assume.assumeNoException("POSIX permissions unavailable", exception); //$NON-NLS-1$
        }
        expect(ConfigurationErrorCode.UNSAFE_CONFIG_FILE, "config", //$NON-NLS-1$
                () -> RuntimeConfigurationLoader.builder().configFile(config).load());

        Path parent = Files.createTempDirectory("runtime-config-parent-"); //$NON-NLS-1$
        Path defaultConfig = parent.resolve("runtime.properties"); //$NON-NLS-1$
        Files.writeString(defaultConfig, "provider.model=safe-model\n", StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.setPosixFilePermissions(defaultConfig, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        Files.setPosixFilePermissions(parent, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_WRITE));
        expect(ConfigurationErrorCode.UNSAFE_CONFIG_FILE, "config", //$NON-NLS-1$
                () -> StrictPropertiesFile.read(defaultConfig, false, true));
    }

    private static RuntimeConfiguration load(Path config, RuntimeSetting setting, String value) {
        return RuntimeConfigurationLoader.builder().configFile(config).override(setting, value).load();
    }

    private static Path config(String text) throws Exception {
        Path file = Files.createTempFile("runtime-config-", ".properties"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    private static void privatePermissionsOrSkip(Path file) throws Exception {
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException exception) {
            // The loader's Windows behavior is deliberately best-effort.
        }
    }

    private static void expect(ConfigurationErrorCode code, RuntimeSetting setting, ThrowingRunnable action) throws Exception {
        expect(code, setting.key(), action);
    }

    private static void expect(ConfigurationErrorCode code, String setting, ThrowingRunnable action) throws Exception {
        try {
            action.run();
            fail("expected configuration failure"); //$NON-NLS-1$
        } catch (ConfigurationException exception) {
            assertEquals(code, exception.code());
            assertEquals(setting, exception.setting());
            assertFalse("error must not include a secret", exception.getMessage().contains("super-secret-value")); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse("error must not include a secret", exception.getMessage().contains("not-allowed")); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse("error must not include a secret", exception.getMessage().contains("raw-secret-value")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }
}
