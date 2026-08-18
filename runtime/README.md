# Standalone runtime reactor

The `runtime` reactor contains plain-Java 17 modules that can be used by a CLI
or another non-Eclipse host:

- `codepilot-runtime-kernel` — host service-provider interfaces;
- `codepilot-runtime-config` — secure, typed provider/MCP/agent configuration;
- `codepilot-runtime-provider` — OpenAI-compatible HTTP transport;
- `codepilot-runtime-mcp-client` — Streamable HTTP MCP client;
- `codepilot-runtime-agent` — provider-neutral bounded agent/tool loop and
  adapters for the provider and MCP client.

Build and test this reactor independently with:

```sh
mvn -f runtime/pom.xml test
```

No runtime module imports Eclipse, OSGi, EDT, or `com.codepilot1c.core` APIs.
The root Tycho reactor also includes these modules, but standalone runtime
development does not require EDT.
