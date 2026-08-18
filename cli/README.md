# CodePilot CLI harness

This Java 17 module is the platform-neutral command surface for running and
inspecting CodePilot integrations outside the EDT UI. It intentionally does
not claim agent execution, an interactive REPL, or EDT process supervision.

Build and run:

```shell
mvn -f cli/pom.xml clean verify
java -jar cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT-all.jar version
java -jar cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT-all.jar --output json doctor
java -jar cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT-all.jar edt installations
java -jar cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT-all.jar edt status
```

Configuration precedence is a Java system property, then an environment
variable, then a safe local default:

| Purpose | System property | Environment |
|---|---|---|
| EDT Eclipse home | `edt.home` | `EDT_HOME` |
| MCP endpoint | `codepilot.endpoint` | `CODEPILOT_ENDPOINT` |
| Optional config file check | `codepilot.config` | `CODEPILOT_CONFIG` |

Discovery checks the explicit EDT home, conventional 1C installation roots,
and `PATH` on macOS, Linux, and Windows. A directory is reported only when it
contains a platform launcher (`1cedtcli`, `1cedt`, or their Windows `.exe`
variants). Discovery never starts
EDT and contains no user-specific hardcoded paths.

`doctor` reports independent `java`, `edt`, `config`, and `endpoint` checks in
text or deterministic JSON. `edt start` and `edt stop` currently return exit
code `4` with `supervisor_unavailable`; they do not pretend the requested
operation happened.

Exit codes:

| Code | Meaning |
|---:|---|
| 0 | Success |
| 1 | Internal command failure |
| 2 | Invalid arguments or configuration |
| 3 | Authentication failure (reserved for provider commands) |
| 4 | EDT unavailable, not ready, or unable to perform the operation |
