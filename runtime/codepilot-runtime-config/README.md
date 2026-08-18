# Runtime configuration contract

`codepilot-runtime-config` is a plain Java 17 module for standalone hosts. It
does not depend on the CLI, Eclipse, OSGi, EDT, or the existing core bundle.

Wave 6A can construct a `RuntimeConfiguration` with
`RuntimeConfigurationLoader.systemDefaults()` and pass its typed values to the
provider, MCP client, and bounded agent runtime. The module intentionally does
not perform those integrations itself.

The expected CLI-agent adapter is deliberately small: take an independent
`ProviderRuntimeSettings` snapshot from `providerSettings()`, map its base URI,
model and timeouts into its provider configuration, and close that snapshot
after use. Map the optional `McpRuntimeSettings.endpoint()` into MCP setup, and
use `AgentRuntimeSettings` for its bounded run configuration. If authentication
is needed, take one `char[]` from the provider snapshot, pass it into the
provider builder, then erase that local array in a `finally` block. The adapter
must close its `RuntimeConfiguration` after the run. It must not add a raw-key
command-line option or translate the key through a `String`.

## Precedence and configuration file

For every setting the deterministic precedence is: explicit overrides, Java
system properties (`codepilot.*`), environment variables (`CODEPILOT_*`), an
optional properties file, then defaults. `RuntimeConfiguration.sourceOf(...)`
reports the winning source.

The optional file is selected from the same precedence for `config`, then the
portable default: `$XDG_CONFIG_HOME/codepilot/runtime.properties` (or
`~/.config`), `~/Library/Application Support/CodePilot/runtime.properties` on
macOS, and `%APPDATA%\\CodePilot\\runtime.properties` on Windows. Only a
small `key=value` format is accepted; unknown, duplicate, escaped, multiline,
or symlinked files are rejected. POSIX config files must not be group/other
writable; the default file's immediate parent must likewise be a non-symlink,
non-group/other-writable directory. Java 17 has no portable Windows ACL check,
so Windows ACL hardening remains an operator responsibility. Files are opened
with `NOFOLLOW_LINKS`, bounded-streamed, and identity-checked before/after open
as far as the Java filesystem API permits. There is no variable interpolation.

Supported ordinary keys are `provider.baseUri`, `provider.model`,
`provider.connectTimeoutMillis`, `provider.requestTimeoutMillis`,
`mcp.endpoint`, `agent.maxSteps`, and `agent.timeoutMillis`. A raw API key is
never accepted in properties, environment, or Java properties. Use only
`provider.apiKeyFile`; it is read into transient `char[]` material. A secret
file path may be supplied by the properties file, `CODEPILOT_PROVIDER_API_KEY_FILE`,
or `-Dcodepilot.provider.apiKeyFile=...`; Java properties may be visible in
process listings and diagnostic tooling, so a protected config file or
environment variable is preferred. Raw `apiKey`, token, password, secret, and
authorization aliases are rejected case-insensitively from all sources.

Provider and MCP HTTPS endpoints may intentionally be remote: configuration is
therefore a trusted local/explicit input boundary. The loader does not DNS
resolve host names (avoiding resolver side effects and rebinding races), so an
embedding host that accepts untrusted endpoint configuration retains an SSRF
risk. Plain HTTP is restricted to `localhost`, `127.0.0.1`, and `[::1]`.
The strict parser rejects secret keys before creating their value string; Java
still cannot guarantee immediate erasure of immutable parser strings created
for ordinary accepted configuration values.

`RuntimeConfiguration`, `RuntimeConfigurationLoader`, and
`ProviderRuntimeSettings` implement `AutoCloseable`.
Call `close()` when the configuration is no longer needed; this erases stored
secret arrays. `copyProviderApiKey()` always returns a caller-owned copy that
the caller must erase after use. Diagnostics and `toString()` report only that
a key is configured, never its value or its file content.
