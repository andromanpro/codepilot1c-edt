#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# Controlled, opt-in E2E check for the packaged CLI. The default fake mode
# never starts EDT; it starts a local process that speaks the small MCP surface
# needed to exercise the CLI supervisor and client end to end.
set -Eeuo pipefail

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
FAKE_LAUNCHER="$ROOT_DIR/tools/cli-e2e/fake-1cedtcli"
DEFAULT_JAR="$ROOT_DIR/cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT-all.jar"

MODE="${CODEPILOT_E2E_MODE:-fake}"
CLI_JAR="${CODEPILOT_CLI_JAR:-$DEFAULT_JAR}"
EDT_HOME="${CODEPILOT_E2E_EDT_HOME:-}"
BUILD_CLI="${CODEPILOT_E2E_BUILD:-false}"
KEEP_ARTIFACTS="${CODEPILOT_E2E_KEEP_ARTIFACTS:-false}"
START_TIMEOUT="${CODEPILOT_E2E_START_TIMEOUT:-30}"

TMP_ROOT=""
TMP_BASE="${TMPDIR:-/tmp}"
TMP_BASE="${TMP_BASE%/}"
RUN_HOME=""
WORKSPACE=""
REGISTRY_DIR=""
INSTANCE_ID=""
INSTANCE_PID=""
INSTANCE_LOG=""
STOPPED="false"

usage() {
    cat <<'EOF'
Usage:
  tools/run-cli-e2e.sh [--jar PATH] [--mode fake|real] [--edt-home PATH]

Modes:
  fake  (default) Use a local MCP-speaking stand-in as the CLI-owned launcher.
  real  Start an explicitly supplied installed EDT. Requires --edt-home.

Environment:
  CODEPILOT_E2E_BUILD=true          Build cli/codepilot-cli before running.
  CODEPILOT_E2E_KEEP_ARTIFACTS=true Keep the temp run directory on exit.
  CODEPILOT_E2E_START_TIMEOUT=30    Readiness timeout passed to edt start.

The harness is intentionally not wired into Maven. It always uses a temporary
Java user.home, registry, and workspace and a loopback ephemeral port.
EOF
}

log() { printf '[cli-e2e] %s\n' "$*" >&2; }
fail() { log "ERROR: $*"; exit 1; }

bool_true() {
    case "${1:-}" in
        1|true|TRUE|True|yes|YES|Yes|on|ON|On) return 0 ;;
        *) return 1 ;;
    esac
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --jar)
            [[ $# -ge 2 ]] || fail "--jar requires a path"
            CLI_JAR=$2
            shift 2
            ;;
        --mode)
            [[ $# -ge 2 ]] || fail "--mode requires fake or real"
            MODE=$2
            shift 2
            ;;
        --edt-home)
            [[ $# -ge 2 ]] || fail "--edt-home requires a path"
            EDT_HOME=$2
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            fail "unknown argument: $1"
            ;;
    esac
done

case "$MODE" in
    fake|real) ;;
    *) fail "mode must be fake or real" ;;
esac

command -v java >/dev/null 2>&1 || fail "java 17+ is required"
command -v python3 >/dev/null 2>&1 || fail "python3 is required for the local fake host and port selection"
[[ -x "$FAKE_LAUNCHER" ]] || fail "fake launcher is not executable: $FAKE_LAUNCHER"

if bool_true "$BUILD_CLI"; then
    log "Building packaged CLI jar (no EDT is launched by this Maven command)"
    (cd "$ROOT_DIR" && mvn -pl cli/codepilot-cli -am -DskipTests package)
fi
[[ -f "$CLI_JAR" && -r "$CLI_JAR" ]] || fail "packaged CLI jar not found: $CLI_JAR (build it or pass --jar)"

TMP_ROOT=$(mktemp -d "$TMP_BASE/codepilot-cli-e2e.XXXXXX")
RUN_HOME="$TMP_ROOT/home"
WORKSPACE="$TMP_ROOT/workspace"
REGISTRY_DIR="$RUN_HOME/.codepilot1c/instances"
mkdir -p "$RUN_HOME" "$WORKSPACE"

# The guard is intentionally narrow so cleanup cannot remove an arbitrary path.
remove_temp_tree() {
    [[ -n "$TMP_ROOT" ]] || return 0
    case "$TMP_ROOT" in
        "$TMP_BASE"/codepilot-cli-e2e.*) ;;
        *) log "Refusing to remove unexpected temp path: $TMP_ROOT"; return 1 ;;
    esac
    rm -rf -- "$TMP_ROOT"
}

pid_is_alive() {
    local pid=${1:-}
    [[ "$pid" =~ ^[0-9]+$ ]] || return 1
    kill -0 "$pid" 2>/dev/null || return 1
    local state
    state=$(ps -p "$pid" -o state= 2>/dev/null | tr -d '[:space:]' || true)
    [[ "$state" != "Z" ]]
}

pid_matches_instance() {
    local pid=${1:-}
    local instance=${2:-}
    [[ "$pid" =~ ^[0-9]+$ && -n "$instance" ]] || return 1
    local command_line marker
    marker="-Dcodepilot.instance.id=$instance"
    command_line=$(ps -p "$pid" -o command= 2>/dev/null || true)
    [[ -n "$command_line" && "$command_line" == *"$marker"* ]]
}

wait_for_exit() {
    local pid=${1:-}
    local attempts=${2:-100}
    for ((i = 0; i < attempts; i++)); do
        pid_is_alive "$pid" || return 0
        sleep 0.1
    done
    return 1
}

kill_exact_process() {
    [[ -n "$INSTANCE_PID" && -n "$INSTANCE_ID" ]] || return 0
    if ! pid_is_alive "$INSTANCE_PID"; then return 0; fi
    if ! pid_matches_instance "$INSTANCE_PID" "$INSTANCE_ID"; then
        log "Refusing fallback termination: PID $INSTANCE_PID no longer matches instance $INSTANCE_ID"
        return 1
    fi
    log "Fallback termination of exact CLI-owned PID $INSTANCE_PID"
    kill "$INSTANCE_PID" 2>/dev/null || true
    if wait_for_exit "$INSTANCE_PID" 100; then return 0; fi
    if pid_matches_instance "$INSTANCE_PID" "$INSTANCE_ID"; then
        kill -9 "$INSTANCE_PID" 2>/dev/null || true
        wait_for_exit "$INSTANCE_PID" 100 || return 1
    else
        log "Refusing force termination: PID identity changed"
        return 1
    fi
}

cleanup() {
    local exit_code=$?
    trap - EXIT INT TERM HUP
    if [[ -n "$INSTANCE_ID" && "$STOPPED" != "true" ]]; then
        log "Cleanup: asking CLI to stop instance $INSTANCE_ID"
        run_cli --output json edt stop --id "$INSTANCE_ID" --force --timeout 5 \
            >"$TMP_ROOT/cleanup-stop.json" 2>"$TMP_ROOT/cleanup-stop.err" || true
    fi
    kill_exact_process || exit_code=1
    if [[ "$exit_code" -ne 0 ]]; then
        if [[ -n "$INSTANCE_LOG" && -f "$INSTANCE_LOG" ]]; then
            log "EDT log tail:"
            tail -n 80 "$INSTANCE_LOG" >&2 || true
        fi
    fi
    if bool_true "$KEEP_ARTIFACTS"; then
        log "Keeping temporary artifacts at $TMP_ROOT"
    else
        remove_temp_tree || exit_code=1
    fi
    exit "$exit_code"
}
trap cleanup EXIT INT TERM HUP

run_cli() {
    # user.home is explicit because Java's user.home derivation differs across
    # desktop platforms. HOME is also isolated for any child library lookup.
    HOME="$RUN_HOME" java -Duser.home="$RUN_HOME" -jar "$CLI_JAR" "$@"
}

json_field() {
    local document=$1
    local path=$2
    python3 - "$document" "$path" <<'PY'
import json
import sys

value = json.loads(sys.argv[1])
for part in sys.argv[2].split('.'):
    if isinstance(value, dict) and part in value:
        value = value[part]
    elif isinstance(value, list) and part.isdigit() and int(part) < len(value):
        value = value[int(part)]
    else:
        raise SystemExit(f"missing JSON field: {sys.argv[2]}")
if isinstance(value, bool):
    print(str(value).lower())
elif value is None:
    print("null")
else:
    print(value)
PY
}

expect_json() {
    local document=$1
    local expression=$2
    python3 - "$document" "$expression" <<'PY'
import json
import sys

value = json.loads(sys.argv[1])
for part in sys.argv[2].split('.'):
    if isinstance(value, dict) and part in value:
        value = value[part]
    elif isinstance(value, list) and part.isdigit() and int(part) < len(value):
        value = value[int(part)]
    else:
        raise SystemExit(f"missing JSON field: {sys.argv[2]}")
if value is not True:
    raise SystemExit(f"expected true: {sys.argv[2]} (got {value!r})")
PY
}

reserve_loopback_port() {
    python3 - <<'PY'
import socket
with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
}

PORT=$(reserve_loopback_port)
[[ "$PORT" =~ ^[0-9]+$ && "$PORT" -ge 1 && "$PORT" -le 65535 ]] || fail "invalid ephemeral port: $PORT"

if [[ "$MODE" == fake ]]; then
    EDT_HOME="$TMP_ROOT/fake-edt"
    mkdir -p "$EDT_HOME"
    # Copy both files so the launcher resolves its sibling script in the
    # temporary EDT home even when the home path contains symlinks.
    cp "$FAKE_LAUNCHER" "$EDT_HOME/1cedtcli"
    cp "$ROOT_DIR/tools/cli-e2e/fake_edt.py" "$EDT_HOME/fake_edt.py"
    chmod +x "$EDT_HOME/1cedtcli" "$EDT_HOME/fake_edt.py"
else
    [[ -n "$EDT_HOME" ]] || fail "real mode requires --edt-home (an explicitly installed EDT)"
    [[ -d "$EDT_HOME" ]] || fail "EDT home is not a directory: $EDT_HOME"
    log "Real EDT mode is enabled explicitly: $EDT_HOME"
fi

log "Starting $MODE CLI-owned headless host on loopback port $PORT"
START_JSON=$(run_cli --output json edt start --workspace "$WORKSPACE" --edt-home "$EDT_HOME" \
    --port "$PORT" --timeout "$START_TIMEOUT") || fail "edt start failed"
[[ "$(json_field "$START_JSON" status)" == ready ]] || fail "start did not report ready: $START_JSON"
INSTANCE_ID=$(json_field "$START_JSON" instance.instanceId)
INSTANCE_PID=$(json_field "$START_JSON" instance.pid)
INSTANCE_LOG=$(json_field "$START_JSON" instance.logFile)
[[ "$INSTANCE_ID" =~ ^[0-9a-fA-F-]{36}$ ]] || fail "invalid instance id from start: $INSTANCE_ID"
[[ "$INSTANCE_PID" =~ ^[0-9]+$ && "$INSTANCE_PID" -gt 0 ]] || fail "invalid PID from start: $INSTANCE_PID"
[[ -f "$INSTANCE_LOG" ]] || fail "supervisor log was not created: $INSTANCE_LOG"
pid_matches_instance "$INSTANCE_PID" "$INSTANCE_ID" \
    || fail "started PID does not carry exact instance marker: $INSTANCE_PID"

STATUS_JSON=$(run_cli --output json edt status --all)
[[ "$(json_field "$STATUS_JSON" instances.0.state)" == ready ]] || fail "status did not report ready: $STATUS_JSON"
[[ "$(json_field "$STATUS_JSON" instances.0.instanceId)" == "$INSTANCE_ID" ]] \
    || fail "status selected a different instance"

HEALTH_JSON=$(run_cli --output json mcp health --instance-id "$INSTANCE_ID")
expect_json "$HEALTH_JSON" "ready"
[[ "$(json_field "$HEALTH_JSON" status)" == ready ]] || fail "MCP health failed: $HEALTH_JSON"

INITIALIZE_JSON=$(run_cli --output json mcp initialize --instance-id "$INSTANCE_ID")
[[ "$(json_field "$INITIALIZE_JSON" status)" == initialized ]] \
    || fail "MCP initialize failed: $INITIALIZE_JSON"
[[ "$(json_field "$INITIALIZE_JSON" protocolVersion)" == 2025-06-18 ]] \
    || fail "unexpected MCP protocol: $INITIALIZE_JSON"

TOOLS_JSON=$(run_cli --output json mcp tools --instance-id "$INSTANCE_ID")
[[ "$(json_field "$TOOLS_JSON" status)" == ok ]] || fail "MCP tools failed: $TOOLS_JSON"
[[ "$(json_field "$TOOLS_JSON" count)" -ge 1 ]] || fail "MCP tools returned no tools: $TOOLS_JSON"

PING_JSON=$(run_cli --output json mcp ping --instance-id "$INSTANCE_ID")
[[ "$(json_field "$PING_JSON" status)" == ok ]] || fail "MCP ping failed: $PING_JSON"

log "Stopping exact CLI-owned instance $INSTANCE_ID (PID $INSTANCE_PID)"
STOP_JSON=$(run_cli --output json edt stop --id "$INSTANCE_ID" --timeout 10) \
    || fail "edt stop failed"
[[ "$(json_field "$STOP_JSON" status)" == stopped ]] || fail "stop did not complete: $STOP_JSON"
STOPPED="true"
wait_for_exit "$INSTANCE_PID" 100 || fail "EDT PID still alive after stop: $INSTANCE_PID"

AFTER_JSON=$(run_cli --output json edt status --all)
[[ "$(json_field "$AFTER_JSON" count)" == 0 ]] || fail "registry was not cleaned: $AFTER_JSON"
if [[ -d "$REGISTRY_DIR" ]] && [[ -n "$(find "$REGISTRY_DIR" -type f -name '*.json' -print -quit)" ]]; then
    fail "registry contains JSON records after stop: $REGISTRY_DIR"
fi
[[ -s "$INSTANCE_LOG" ]] || fail "supervisor log is empty: $INSTANCE_LOG"
if [[ "$MODE" == fake ]]; then
    grep -F "fake-edt-ready instance=$INSTANCE_ID" "$INSTANCE_LOG" >/dev/null \
        || fail "fake host readiness diagnostics missing from log"
    grep -F "fake-edt-shutdown instance=$INSTANCE_ID" "$INSTANCE_LOG" >/dev/null \
        || fail "fake host shutdown diagnostics missing from log"
fi

log "PASS: start/readiness/status/MCP initialize/tools/ping/stop/cleanup"
exit 0
