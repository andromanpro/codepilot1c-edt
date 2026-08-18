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
TMP_MARKER=""
RUN_HOME=""
WORKSPACE=""
REGISTRY_DIR=""
INSTANCE_ID=""
INSTANCE_PID=""
INSTANCE_LOG=""
START_CLI_PID=""
START_PID_FILE=""
START_STDOUT=""
START_STDERR=""
TOKEN_FILE=""
PACKAGED_ROOT=""
PACKAGED_CLI=""
PACKAGED_JAVA=""
SHELL_SESSION_ID=""

# Installed as soon as the owned marker exists. This deliberately has no
# dependency on the later full cleanup functions, so setup failures (including
# an invalid HOME) cannot leak the newly allocated temp root.
minimal_owned_root_cleanup() {
    local exit_code=$?
    trap - EXIT INT TERM HUP
    if [[ -n "$TMP_ROOT" && -n "$TMP_MARKER" \
        && -d "$TMP_ROOT" && ! -L "$TMP_ROOT" \
        && -f "$TMP_MARKER" && ! -L "$TMP_MARKER" ]]; then
        local actual marker_value
        actual=$(CDPATH= cd -- "$TMP_ROOT" && pwd -P 2>/dev/null) || actual=""
        marker_value=$(<"$TMP_MARKER") || marker_value=""
        if [[ "$actual" == "$TMP_ROOT" && "$marker_value" == "$TMP_ROOT" ]]; then
            rm -rf -- "$TMP_ROOT"
        fi
    fi
    exit "$exit_code"
}

# Trap before argument validation, builds, or allocation. Once TMP_ROOT and its
# marker exist, even a setup-time signal is routed through the narrow cleanup.
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP
trap minimal_owned_root_cleanup EXIT

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
Java user.home, registry, and workspace and a loopback ephemeral port. Python
3.10+ is required by the local fake host; start retries are bounded to three
attempts for port/process races.
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
python3 -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)' \
    || fail "python3 3.10+ is required"
[[ -x "$FAKE_LAUNCHER" ]] || fail "fake launcher is not executable: $FAKE_LAUNCHER"

if bool_true "$BUILD_CLI"; then
    log "Building packaged CLI jar (no EDT is launched by this Maven command)"
    (cd "$ROOT_DIR" && mvn -pl cli/codepilot-cli -am -DskipTests package)
fi
[[ -f "$CLI_JAR" && -r "$CLI_JAR" ]] || fail "packaged CLI jar not found: $CLI_JAR (build it or pass --jar)"

TMP_ROOT=$(env -u TMPDIR -u TMP -u TEMP mktemp -d -t codepilot-cli-e2e)
TMP_ROOT=$(CDPATH= cd -- "$TMP_ROOT" && pwd -P)
TMP_MARKER="$TMP_ROOT/.codepilot-cli-e2e-owned"
printf '%s\n' "$TMP_ROOT" >"$TMP_MARKER"
chmod 600 "$TMP_MARKER"
RUN_HOME="$TMP_ROOT/home"
WORKSPACE="$TMP_ROOT/workspace"
REGISTRY_DIR="$RUN_HOME/.codepilot1c/instances"
TOKEN_FILE="$TMP_ROOT/mcp-token"
START_PID_FILE="$TMP_ROOT/start.pid"
mkdir -p "$RUN_HOME" "$WORKSPACE" "$REGISTRY_DIR"
printf '%s\n' 'wave4-e1-e2e-private-token' >"$TOKEN_FILE"
chmod 600 "$TOKEN_FILE"

path_is_within() {
    local child=$1
    local parent=$2
    [[ "$child" == "$parent" || "$child" == "$parent"/* ]]
}

USER_HOME_REAL=$(CDPATH= cd -- "${HOME:-/}" && pwd -P)
path_is_within "$TMP_ROOT" "$ROOT_DIR" && fail "temp root overlaps repository"
path_is_within "$TMP_ROOT" "$USER_HOME_REAL" && fail "temp root overlaps user home"
[[ "$TMP_ROOT" != "/" && "$TMP_ROOT" != "." ]] || fail "unsafe temp root"

# The guard is intentionally narrow so cleanup cannot remove an arbitrary path.
remove_temp_tree() {
    [[ -n "$TMP_ROOT" ]] || return 0
    [[ -d "$TMP_ROOT" && ! -L "$TMP_ROOT" ]] || { log "Refusing to remove unsafe temp root: $TMP_ROOT"; return 1; }
    [[ -f "$TMP_MARKER" && ! -L "$TMP_MARKER" ]] || { log "Temp ownership marker missing"; return 1; }
    local actual marker_value
    actual=$(CDPATH= cd -- "$TMP_ROOT" && pwd -P) || return 1
    marker_value=$(<"$TMP_MARKER")
    [[ "$actual" == "$TMP_ROOT" && "$marker_value" == "$TMP_ROOT" ]] || {
        log "Refusing to remove temp root whose canonical path/marker changed"
        return 1
    }
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

pid_command_line() {
    local pid=${1:-}
    [[ "$pid" =~ ^[0-9]+$ ]] || return 1
    ps -p "$pid" -o command= 2>/dev/null || true
}

pid_matches_start() {
    local pid=${1:-}
    local command_line
    command_line=$(pid_command_line "$pid")
    [[ "$command_line" == *"-Duser.home=$RUN_HOME"* \
        && "$command_line" == *"-jar $CLI_JAR"* \
        && "$command_line" == *"edt start"* \
        && "$command_line" == *"--workspace $WORKSPACE"* ]]
}

wait_for_exit() {
    local pid=${1:-}
    local attempts=${2:-100}
    local i
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

kill_exact_start_process() {
    capture_unassigned_start_pid || return 1
    [[ -n "$START_CLI_PID" ]] || return 0
    if ! pid_is_alive "$START_CLI_PID"; then START_CLI_PID=""; return 0; fi
    if ! pid_matches_start "$START_CLI_PID"; then
        if ! pid_is_alive "$START_CLI_PID"; then START_CLI_PID=""; return 0; fi
        log "Refusing fallback termination: start PID identity changed ($START_CLI_PID)"
        return 1
    fi
    log "Terminating exact interrupted CLI start PID $START_CLI_PID"
    kill "$START_CLI_PID" 2>/dev/null || true
    if wait_for_exit "$START_CLI_PID" 100; then START_CLI_PID=""; return 0; fi
    if pid_matches_start "$START_CLI_PID"; then
        kill -9 "$START_CLI_PID" 2>/dev/null || true
        wait_for_exit "$START_CLI_PID" 100 || return 1
        START_CLI_PID=""
    elif ! pid_is_alive "$START_CLI_PID"; then
        START_CLI_PID=""
        return 0
    else
        log "Refusing force termination: start PID identity changed"
        return 1
    fi
}

capture_unassigned_start_pid() {
    [[ -z "$START_CLI_PID" ]] || return 0
    local candidate="" job attempt
    for attempt in 1 2 3 4 5 6 7 8 9 10; do
        if [[ -f "$START_PID_FILE" && ! -L "$START_PID_FILE" ]]; then
            candidate=$(<"$START_PID_FILE") || candidate=""
        fi
        if [[ ! "$candidate" =~ ^[0-9]+$ ]]; then
            while IFS= read -r job; do
                [[ "$job" =~ ^[0-9]+$ ]] || continue
                if pid_matches_start "$job"; then candidate=$job; break; fi
            done < <(jobs -pr 2>/dev/null || true)
        fi
        if [[ "$candidate" =~ ^[0-9]+$ ]] && pid_matches_start "$candidate"; then
            START_CLI_PID=$candidate
            log "Recovered exact CLI start PID $START_CLI_PID before normal PID publication"
            return 0
        fi
        pid_is_alive "$candidate" || candidate=""
        sleep 0.02
    done
    if [[ -n "$candidate" ]] && pid_is_alive "$candidate"; then
        START_CLI_PID=$candidate
        log "Could not validate an unassigned live start PID: $candidate"
        return 1
    fi
    return 0
}

registry_record_lines() {
    python3 - "$REGISTRY_DIR" <<'PY'
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
if not root.is_dir() or root.is_symlink():
    raise SystemExit(0)
for path in sorted(root.glob("*.json")):
    if path.is_symlink() or not path.is_file():
        print(f"invalid\t{path}")
        continue
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        print("ok\t{}\t{}\t{}\t{}".format(
            value["instanceId"], value["pid"], value["owner"], value["workspace"]))
    except (OSError, ValueError, KeyError, TypeError):
        print(f"invalid\t{path}")
PY
}

reconcile_temp_instances() {
    local unsafe=0 valid id pid owner workspace
    [[ -d "$REGISTRY_DIR" && ! -L "$REGISTRY_DIR" ]] || return 0
    while IFS=$'\t' read -r valid id pid owner workspace; do
        [[ -n "$valid" ]] || continue
        if [[ "$valid" != ok || "$owner" != cli || "$workspace" != "$WORKSPACE" \
            || ! "$id" =~ ^[0-9a-fA-F-]{36}$ || ! "$pid" =~ ^[0-9]+$ ]]; then
            log "Refusing to mutate unexpected temp registry record"
            unsafe=1
            continue
        fi
        if pid_is_alive "$pid" && ! pid_matches_instance "$pid" "$id"; then
            log "Refusing to terminate registry PID with mismatched identity: $pid/$id"
            unsafe=1
            continue
        fi
        run_cli --output json edt stop --id "$id" --force --timeout 5 \
            >"$TMP_ROOT/reconcile-$id.json" 2>"$TMP_ROOT/reconcile-$id.err" || unsafe=1
    done < <(registry_record_lines)
    return "$unsafe"
}

temp_live_processes_remain() {
    local valid id pid owner workspace
    while IFS=$'\t' read -r valid id pid owner workspace; do
        [[ "$valid" == ok ]] || return 0
        [[ "$owner" == cli && "$workspace" == "$WORKSPACE" ]] || return 0
        if pid_is_alive "$pid"; then return 0; fi
    done < <(registry_record_lines)
    return 1
}

cleanup() {
    local exit_code=$?
    trap - EXIT INT TERM HUP
    local unsafe=0
    kill_exact_start_process || unsafe=1
    reconcile_temp_instances || unsafe=1
    kill_exact_process || unsafe=1
    temp_live_processes_remain && unsafe=1 || true
    if [[ "$unsafe" -ne 0 ]]; then
        log "Cleanup could not prove all temp-owned processes are stopped; preserving root"
        exit_code=1
    fi
    if [[ "$exit_code" -ne 0 ]]; then
        if [[ -n "$INSTANCE_LOG" && -f "$INSTANCE_LOG" ]]; then
            log "EDT log tail:"
            tail -n 80 "$INSTANCE_LOG" >&2 || true
        fi
    fi
    if bool_true "$KEEP_ARTIFACTS" || [[ "$unsafe" -ne 0 ]]; then
        log "Keeping temporary artifacts at $TMP_ROOT"
    else
        remove_temp_tree || exit_code=1
    fi
    exit "$exit_code"
}
# Preserve a non-zero interruption status while still running EXIT cleanup.
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP
trap cleanup EXIT

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

start_cli_once() {
    PORT=$(reserve_loopback_port)
    [[ "$PORT" =~ ^[0-9]+$ && "$PORT" -ge 1 && "$PORT" -le 65535 ]] \
        || fail "invalid ephemeral port: $PORT"
    START_STDOUT="$TMP_ROOT/start.stdout"
    START_STDERR="$TMP_ROOT/start.stderr"
    : >"$START_STDOUT"
    : >"$START_STDERR"
    log "Starting $MODE CLI-owned headless host on loopback port $PORT"
    set +e
    HOME="$RUN_HOME" java -Duser.home="$RUN_HOME" -jar "$CLI_JAR" --output json edt start \
        --workspace "$WORKSPACE" --edt-home "$EDT_HOME" --port "$PORT" --timeout "$START_TIMEOUT" \
        >"$START_STDOUT" 2>"$START_STDERR" &
    local launched_pid=$!
    printf '%s\n' "$launched_pid" >"$START_PID_FILE"
    # Internal safety-test seam: cleanup can recover the exact child from the
    # private PID file even if a signal arrives before START_CLI_PID is set.
    if [[ -n "${CODEPILOT_E2E_TEST_PRE_PID_DELAY:-}" ]]; then
        [[ "$CODEPILOT_E2E_TEST_PRE_PID_DELAY" =~ ^[0-9]+$ ]] \
            || fail "CODEPILOT_E2E_TEST_PRE_PID_DELAY must be whole seconds"
        sleep "$CODEPILOT_E2E_TEST_PRE_PID_DELAY"
    fi
    START_CLI_PID=$launched_pid
    wait "$START_CLI_PID"
    local rc=$?
    set -e
    # The PID is cleared only after wait; an interruption trap sees it and can
    # verify/terminate exactly this start command.
    START_CLI_PID=""
    : >"$START_PID_FILE"
    START_JSON=$(<"$START_STDOUT")
    return "$rc"
}

START_OK="false"
for attempt in 1 2 3; do
    if start_cli_once; then
        START_OK="true"
        break
    fi
    if ! grep -Eq 'port_unavailable|process_start_failed|process_exited' "$START_STDOUT" "$START_STDERR"; then
        break
    fi
    log "Retrying bounded start after possible port race (attempt $attempt/3)"
    reconcile_temp_instances || fail "unsafe temp registry after start retry"
done
[[ "$START_OK" == true ]] || {
    cat "$START_STDOUT" "$START_STDERR" >&2 || true
    fail "edt start failed"
}
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

MCP_AUTH_ARGS=()
if [[ "$MODE" == fake ]]; then
    MCP_AUTH_ARGS=(--bearer-token-file "$TOKEN_FILE")
fi

HEALTH_JSON=$(run_cli --output json mcp "${MCP_AUTH_ARGS[@]}" health --instance-id "$INSTANCE_ID")
expect_json "$HEALTH_JSON" "ready"
[[ "$(json_field "$HEALTH_JSON" status)" == ready ]] || fail "MCP health failed: $HEALTH_JSON"

INITIALIZE_JSON=$(run_cli --output json mcp "${MCP_AUTH_ARGS[@]}" \
    initialize --instance-id "$INSTANCE_ID")
[[ "$(json_field "$INITIALIZE_JSON" status)" == initialized ]] \
    || fail "MCP initialize failed: $INITIALIZE_JSON"
if [[ "$MODE" == fake ]]; then
    [[ "$(json_field "$INITIALIZE_JSON" protocolVersion)" == 2025-11-25 ]] \
        || fail "unexpected MCP protocol: $INITIALIZE_JSON"
fi

TOOLS_JSON=$(run_cli --output json mcp "${MCP_AUTH_ARGS[@]}" \
    tools --instance-id "$INSTANCE_ID")
[[ "$(json_field "$TOOLS_JSON" status)" == ok ]] || fail "MCP tools failed: $TOOLS_JSON"
[[ "$(json_field "$TOOLS_JSON" count)" -ge 1 ]] || fail "MCP tools returned no tools: $TOOLS_JSON"

PING_JSON=$(run_cli --output json mcp "${MCP_AUTH_ARGS[@]}" \
    ping --instance-id "$INSTANCE_ID")
[[ "$(json_field "$PING_JSON" status)" == ok ]] || fail "MCP ping failed: $PING_JSON"

PACKAGED_ROOT="$TMP_ROOT/packaged distribution"
PACKAGED_CLI="$PACKAGED_ROOT/bin/codepilot"
PACKAGED_JAVA="$TMP_ROOT/java-with-private-home"
mkdir -p "$PACKAGED_ROOT/bin" "$PACKAGED_ROOT/lib"
cp "$ROOT_DIR/packaging/launchers/codepilot" "$PACKAGED_CLI"
cp "$CLI_JAR" "$PACKAGED_ROOT/lib/codepilot-cli.jar"
chmod +x "$PACKAGED_CLI"
printf '%s\n' '#!/bin/sh' \
    'exec "$CODEPILOT_E2E_JAVA" "-Duser.home=$CODEPILOT_E2E_RUN_HOME" "$@"' \
    >"$PACKAGED_JAVA"
chmod +x "$PACKAGED_JAVA"

run_packaged() {
    HOME="$RUN_HOME" CODEPILOT_JAVA="$PACKAGED_JAVA" \
        CODEPILOT_E2E_JAVA="$(command -v java)" CODEPILOT_E2E_RUN_HOME="$RUN_HOME" \
        "$PACKAGED_CLI" "$@"
}

PACKAGED_VERSION=$(run_packaged version) \
    || fail "packaged POSIX launcher version smoke failed"
[[ "$PACKAGED_VERSION" == codepilot\ * ]] \
    || fail "packaged POSIX launcher returned unexpected version output: $PACKAGED_VERSION"

if [[ "$MODE" == fake ]]; then
CALL_JSON=$(run_cli --output json mcp "${MCP_AUTH_ARGS[@]}" \
    call --instance-id "$INSTANCE_ID" e2e_echo --args '{"value":"approved-wave4"}')
[[ "$(json_field "$CALL_JSON" status)" == ok ]] || fail "MCP call failed: $CALL_JSON"
[[ "$(json_field "$CALL_JSON" isError)" == false ]] || fail "MCP call returned an error: $CALL_JSON"

cat >"$TMP_ROOT/shell-first.stdin" <<'EOF'
Run the approved Wave 4 tool.
y
/sessions
/status
/exit
EOF
run_packaged shell --mode connected --instance-id "$INSTANCE_ID" \
    --mcp-bearer-token-file "$TOKEN_FILE" --turn-timeout 30 \
    <"$TMP_ROOT/shell-first.stdin" >"$TMP_ROOT/shell-first.stdout" \
    2>"$TMP_ROOT/shell-first.stderr" || fail "packaged connected shell scenario failed"

SHELL_SESSION_ID=$(python3 - "$RUN_HOME/.codepilot1c/sessions" <<'PY'
import json
import os
import pathlib
import stat
import sys
import uuid

root = pathlib.Path(sys.argv[1])
if not root.is_dir() or root.is_symlink():
    raise SystemExit("private session root is missing or is a symlink")
if os.name == "posix" and stat.S_IMODE(root.stat().st_mode) != 0o700:
    raise SystemExit("private session root is not mode 0700")
metadata_files = sorted(root.glob("*.meta.json"))
transcript_files = sorted(root.glob("*.jsonl"))
if len(metadata_files) != 1 or len(transcript_files) != 1:
    raise SystemExit("expected exactly one metadata and one transcript file")
for path in metadata_files + transcript_files:
    if path.is_symlink() or not path.is_file():
        raise SystemExit("session entry is not a regular private file")
    if os.name == "posix" and stat.S_IMODE(path.stat().st_mode) != 0o600:
        raise SystemExit(f"session file is not mode 0600: {path.name}")
metadata = json.loads(metadata_files[0].read_text(encoding="utf-8"))
session_id = str(uuid.UUID(metadata["id"]))
if metadata_files[0].name != f"{session_id}.meta.json":
    raise SystemExit("metadata filename does not match its UUID")
if metadata["schemaVersion"] != 1 or metadata["mode"] != "connected":
    raise SystemExit("unexpected session schema or mode")
if metadata["turns"] != 1 or metadata["messageCount"] != 4:
    raise SystemExit("unexpected session counts")
records = [json.loads(line) for line in transcript_files[0].read_text(
    encoding="utf-8").splitlines() if line]
if [record.get("type") for record in records] != ["text", "assistant", "tool", "assistant"]:
    raise SystemExit("unexpected session transcript sequence")
if records[0].get("content") != "Run the approved Wave 4 tool.":
    raise SystemExit("session did not persist the scripted user turn")
if records[-1].get("text") != "Wave 4 connected shell complete.":
    raise SystemExit("session did not persist the scripted final text")
print(session_id)
PY
) || fail "private shell session files are invalid"

cat >"$TMP_ROOT/shell-resume.stdin" <<EOF
/resume $SHELL_SESSION_ID
/status
/exit
EOF
run_packaged shell --mode connected --instance-id "$INSTANCE_ID" \
    --mcp-bearer-token-file "$TOKEN_FILE" --turn-timeout 30 \
    <"$TMP_ROOT/shell-resume.stdin" >"$TMP_ROOT/shell-resume.stdout" \
    2>"$TMP_ROOT/shell-resume.stderr" || fail "packaged session resume scenario failed"

grep -F "Approval required: Wave 4 E2E echo [e2e_echo, confirmation required]" \
    "$TMP_ROOT/shell-first.stdout" >/dev/null || fail "tool approval prompt was not rendered"
grep -F "tool-call e2e_echo [wave4-call-1]" "$TMP_ROOT/shell-first.stdout" >/dev/null \
    || fail "tool call was not rendered"
grep -F "tool-result e2e_echo [wave4-call-1] ok" "$TMP_ROOT/shell-first.stdout" >/dev/null \
    || fail "approved MCP result was not rendered"
grep -F "Wave 4 connected shell complete." "$TMP_ROOT/shell-first.stdout" >/dev/null \
    || fail "scripted final assistant text was not rendered"
grep -F "$SHELL_SESSION_ID  1 turn(s)" "$TMP_ROOT/shell-first.stdout" >/dev/null \
    || fail "/sessions did not list the persisted session"
grep -F "mode=connected provider=Fake Connected Provider model=wave4-e2e-model" \
    "$TMP_ROOT/shell-first.stdout" >/dev/null || fail "/status did not report connected mode"
grep -F "Resumed session: $SHELL_SESSION_ID (1 turn(s))" \
    "$TMP_ROOT/shell-resume.stdout" >/dev/null || fail "/resume did not load the session"
grep -F "session=$SHELL_SESSION_ID turns=1" "$TMP_ROOT/shell-resume.stdout" >/dev/null \
    || fail "resumed /status did not report the saved session"
if grep -E 'error:|Turn ended:' "$TMP_ROOT/shell-first.stdout" "$TMP_ROOT/shell-resume.stdout" >/dev/null; then
    fail "shell scenario reported an error"
fi
fi

log "Stopping exact CLI-owned instance $INSTANCE_ID (PID $INSTANCE_PID)"
STOP_JSON=$(run_cli --output json edt stop --id "$INSTANCE_ID" --timeout 10) \
    || fail "edt stop failed"
[[ "$(json_field "$STOP_JSON" status)" == stopped ]] || fail "stop did not complete: $STOP_JSON"
wait_for_exit "$INSTANCE_PID" 100 || fail "EDT PID still alive after stop: $INSTANCE_PID"

AFTER_JSON=$(run_cli --output json edt status --all)
[[ "$(json_field "$AFTER_JSON" count)" == 0 ]] || fail "registry was not cleaned: $AFTER_JSON"
if [[ -d "$REGISTRY_DIR" ]] && [[ -n "$(find "$REGISTRY_DIR" -type f -name '*.json' -print -quit)" ]]; then
    fail "registry contains JSON records after stop: $REGISTRY_DIR"
fi
[[ -s "$INSTANCE_LOG" ]] || fail "supervisor log is empty: $INSTANCE_LOG"
if [[ "$MODE" == fake ]]; then
    EXPECTED_CONTRACT_LOG="fake-edt-contract-ok application=com.codepilot1c.core.headless "
    EXPECTED_CONTRACT_LOG+="workspace=$WORKSPACE port=$PORT bind=127.0.0.1 instance=$INSTANCE_ID "
    EXPECTED_CONTRACT_LOG+="owner=cli registry=$REGISTRY_DIR argv=strict"
    grep -F "$EXPECTED_CONTRACT_LOG" "$INSTANCE_LOG" >/dev/null \
        || fail "fake launcher contract diagnostics missing from log"
    grep -F "fake-edt-ready instance=$INSTANCE_ID" "$INSTANCE_LOG" >/dev/null \
        || fail "fake host readiness diagnostics missing from log"
    grep -F "fake-edt-session-delete instance=$INSTANCE_ID" "$INSTANCE_LOG" >/dev/null \
        || fail "MCP session DELETE diagnostics missing from log"
    grep -F "fake-edt-mcp-ok method=initialize" "$INSTANCE_LOG" >/dev/null \
        || fail "authenticated MCP initialize diagnostics missing from log"
    grep -F "fake-edt-mcp-ok method=tools/list" "$INSTANCE_LOG" >/dev/null \
        || fail "authenticated MCP tools/list diagnostics missing from log"
    grep -F "fake-edt-mcp-ok method=tools/call" "$INSTANCE_LOG" >/dev/null \
        || fail "authenticated MCP tools/call diagnostics missing from log"
    grep -F "fake-edt-mcp-ok method=ping" "$INSTANCE_LOG" >/dev/null \
        || fail "authenticated MCP ping diagnostics missing from log"
    grep -F "fake-edt-auth-ok route=llm-capabilities" "$INSTANCE_LOG" >/dev/null \
        || fail "authenticated LLM capabilities diagnostics missing from log"
    grep -F "fake-edt-llm-ok turn=1" "$INSTANCE_LOG" >/dev/null \
        || fail "scripted broker tool-call turn diagnostics missing from log"
    grep -F "fake-edt-llm-ok turn=2" "$INSTANCE_LOG" >/dev/null \
        || fail "scripted broker final-text turn diagnostics missing from log"
    grep -F 'fake-edt-summary active_sessions=0 deletes=6 chat_turns=2' "$INSTANCE_LOG" >/dev/null \
        || fail "fake host session lifecycle summary is incomplete"
    grep -F "fake-edt-shutdown instance=$INSTANCE_ID" "$INSTANCE_LOG" >/dev/null \
        || fail "fake host shutdown diagnostics missing from log"
    if grep -F 'fake-edt-http-contract-failure' "$INSTANCE_LOG" >/dev/null; then
        fail "fake host observed an HTTP contract violation"
    fi
fi

python3 - "$TMP_ROOT" "$TOKEN_FILE" <<'PY'
import os
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
token_file = pathlib.Path(sys.argv[2])
secret = b"wave4-e1-e2e-private-token"
for directory, names, files in os.walk(root, followlinks=False):
    names[:] = [name for name in names if not (pathlib.Path(directory) / name).is_symlink()]
    for name in files:
        path = pathlib.Path(directory) / name
        if path == token_file or path.is_symlink():
            continue
        try:
            value = path.read_bytes()
        except OSError as failure:
            raise SystemExit(f"cannot inspect runtime artifact {path}: {failure}")
        if secret in value:
            raise SystemExit(f"test bearer leaked into runtime artifact: {path}")
PY

PROCESS_SNAPSHOT=$(ps -ax -o pid=,command= 2>/dev/null || true)
if [[ "$PROCESS_SNAPSHOT" == *"wave4-e1-e2e-private-token"* ]]; then
    fail "test bearer leaked into a process command line"
fi
while IFS= read -r process_line; do
    [[ "$process_line" == *"$TMP_ROOT"* ]] || continue
    fail "temporary E2E process remains after stop: $process_line"
done <<<"$PROCESS_SNAPSHOT"

log "PASS: packaged launcher/shell/session + authenticated MCP/LLM + stop/cleanup"
exit 0
