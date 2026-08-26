# WP-N0 scoped confirmation profile handoff

This is a configuration handoff only. It does not authorize an install, restart,
or change to any user EDT instance.

For an MCP host that must perform the one protected semantic operation, select
the explicit `gsd-execute` session profile, set the host mutation decision to
`ALLOW`, and expose only the needed tools. The corresponding launch properties
are:

```text
-Dcodepilot.mcp.host.policy.sessionProfile=gsd-execute
-Dcodepilot.mcp.host.policy.defaultMutationDecision=ALLOW
-Dcodepilot.mcp.host.policy.exposedTools=edt_validate_request,create_metadata,edt_metadata_details
```

`ALLOW` does not provide blanket mutation permission here. `gsd-execute` still
allowlists tools and marks `create_metadata` as confirmation-required. The host
admits that specific tool only when it carries a nonblank `validation_token` and
its `@ToolMeta` contract declares `requiresValidationToken=true`; the tool then
verifies one-time operation/project/payload binding before mutation. Empty or
unknown profiles, missing tokens, profile-denied tools, mismatched payloads,
replays, and expired tokens remain denied.

When the host returns `profile_required_for_scoped_confirmation`, select an
explicit permitted engineering session profile. When it returns
`profile_unresolved`, correct the selected profile identifier. Do not paste
tokens into logs, tickets, shell history, or configuration files.
