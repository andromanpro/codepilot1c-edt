#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Strict local stand-in for the headless EDT MCP and LLM broker surfaces."""

from __future__ import annotations

import json
import os
import signal
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any


PROTOCOL = "2025-11-25"
AUTH_TOKEN = "-".join(("wave4", "e1", "e2e", "private", "token"))
AUTHORIZATION = f"Bearer {AUTH_TOKEN}"
TOOL_NAME = "e2e_echo"
TOOL_DESCRIPTION = "Deterministic mutating Wave 4 E2E tool"
TOOL_SCHEMA = {
    "type": "object",
    "properties": {"value": {"type": "string"}},
    "required": ["value"],
    "additionalProperties": False,
}
TOOL_CALL_ID = "wave4-call-1"
TOOL_ARGUMENTS = {"value": "approved-wave4"}
TOOL_RESULT_TEXT = "approved-wave4-tool-result"
SHELL_PROMPT = "Run the approved Wave 4 tool."
FINAL_TEXT = "Wave 4 connected shell complete."


def option_value(prefix: str) -> str | None:
    values = [argument[len(prefix) :] for argument in sys.argv[1:] if argument.startswith(prefix)]
    return values[0] if len(values) == 1 else None


def positional_value(option: str) -> str | None:
    positions = [index for index, argument in enumerate(sys.argv[1:]) if argument == option]
    if len(positions) != 1:
        return None
    position = positions[0] + 1
    arguments = sys.argv[1:]
    return arguments[position] if position < len(arguments) else None


PORT_TEXT = option_value("-Dcodepilot.mcp.host.http.port=")
INSTANCE_ID = option_value("-Dcodepilot.instance.id=") or "unknown"
REGISTRY_DIR = option_value("-Dcodepilot.instance.registryDir=") or "unknown"
WORKSPACE = positional_value("-data") or ""
READY_DELAY = float(os.environ.get("CODEPILOT_FAKE_EDT_READY_DELAY", "0"))
READY_AT = time.monotonic() + max(0.0, READY_DELAY)

CONTRACT_ERRORS: list[str] = []
try:
    PORT = int(PORT_TEXT or "0")
except ValueError:
    PORT = 0

EXPECTED_ARGUMENTS = [
    "-nosplash",
    "-application",
    "com.codepilot1c.core.headless",
    "-data",
    WORKSPACE,
    "-vmargs",
    "-Dcodepilot.mcp.enabled=true",
    "-Dcodepilot.mcp.host.enabled=true",
    "-Dcodepilot.mcp.host.http.enabled=true",
    "-Dcodepilot.mcp.host.http.bindAddress=127.0.0.1",
    f"-Dcodepilot.mcp.host.http.port={PORT_TEXT}",
    f"-Dcodepilot.instance.id={INSTANCE_ID}",
    "-Dcodepilot.instance.owner=cli",
    f"-Dcodepilot.instance.registryDir={REGISTRY_DIR}",
]
if sys.argv[1:] != EXPECTED_ARGUMENTS:
    CONTRACT_ERRORS.append("launcher argv does not match the exact EDT headless contract")
if not WORKSPACE or not os.path.isabs(WORKSPACE) or not os.path.isdir(WORKSPACE):
    CONTRACT_ERRORS.append("-data must be an existing absolute workspace")
elif os.path.realpath(WORKSPACE) != WORKSPACE:
    CONTRACT_ERRORS.append("-data must be canonical")
if not REGISTRY_DIR or not os.path.isabs(REGISTRY_DIR) or not os.path.isdir(REGISTRY_DIR):
    CONTRACT_ERRORS.append("registryDir must be an existing absolute directory")
elif os.path.realpath(REGISTRY_DIR) != REGISTRY_DIR:
    CONTRACT_ERRORS.append("registryDir must be canonical")
try:
    if str(uuid.UUID(INSTANCE_ID)) != INSTANCE_ID.lower():
        raise ValueError
except ValueError:
    CONTRACT_ERRORS.append("instance id must be a canonical UUID")
if PORT < 1 or PORT > 65535:
    CONTRACT_ERRORS.append("HTTP port must be in the range 1..65535")

STATE_LOCK = threading.Lock()
ACTIVE_SESSIONS: set[str] = set()
SESSION_SEQUENCE = 0
DELETE_COUNT = 0
METHOD_COUNTS: dict[str, int] = {}
CHAT_TURNS = 0


def count_method(method: str) -> None:
    with STATE_LOCK:
        METHOD_COUNTS[method] = METHOD_COUNTS.get(method, 0) + 1


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = False


class Handler(BaseHTTPRequestHandler):
    server_version = "CodePilotE2EFakeEDT/2"

    def log_message(self, format: str, *args: object) -> None:
        print(f"fake-edt-http {self.address_string()} {format % args}", flush=True)

    def send_json(
        self, status: int, body: object, *, session_id: str | None = None
    ) -> None:
        payload = json.dumps(body, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        if session_id is not None:
            self.send_header("Mcp-Session-Id", session_id)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def reject(self, status: int, route: str, reason: str) -> None:
        print(f"fake-edt-http-contract-failure route={route} reason={reason}", flush=True)
        self.send_json(status, {"error": "contract_failure", "reason": reason})

    def authorized(self, route: str) -> bool:
        if self.headers.get("Authorization") == AUTHORIZATION:
            print(f"fake-edt-auth-ok route={route}", flush=True)
            return True
        self.reject(401, route, "missing or invalid bearer authorization")
        return False

    def mcp_headers_valid(self, route: str, *, session_required: bool) -> bool:
        if not self.authorized(route):
            return False
        if self.headers.get("MCP-Protocol-Version") != PROTOCOL:
            self.reject(400, route, "missing or invalid MCP protocol header")
            return False
        accept = self.headers.get("Accept") or ""
        if self.command == "POST" and not all(
            media_type in accept for media_type in ("application/json", "text/event-stream")
        ):
            self.reject(406, route, "MCP POST Accept header is incomplete")
            return False
        if self.command == "DELETE" and "application/json" not in accept:
            self.reject(406, route, "MCP DELETE Accept header is incomplete")
            return False
        session_id = self.headers.get("Mcp-Session-Id")
        if session_required:
            with STATE_LOCK:
                active = session_id in ACTIVE_SESSIONS
            if not session_id or not active:
                self.reject(404, route, "missing, unknown, or deleted MCP session")
                return False
        elif session_id is not None:
            self.reject(400, route, "initialize must not carry an MCP session")
            return False
        return True

    def read_json(self, route: str) -> dict[str, Any] | None:
        if not (self.headers.get("Content-Type") or "").lower().startswith("application/json"):
            self.reject(415, route, "content type must be application/json")
            return None
        try:
            length = int(self.headers.get("Content-Length", "-1"))
            if length < 0 or length > 1024 * 1024:
                raise ValueError
            value = json.loads(self.rfile.read(length).decode("utf-8"))
            if not isinstance(value, dict):
                raise ValueError
            return value
        except (UnicodeDecodeError, ValueError, json.JSONDecodeError):
            self.reject(400, route, "request body must be a bounded JSON object")
            return None

    def do_GET(self) -> None:  # noqa: N802 - http.server API
        if self.path == "/health/ready":
            authorization = self.headers.get("Authorization")
            if authorization is not None and authorization != AUTHORIZATION:
                self.reject(401, "health", "invalid bearer authorization")
                return
            if authorization == AUTHORIZATION:
                print("fake-edt-auth-ok route=health", flush=True)
            if time.monotonic() < READY_AT:
                self.send_json(503, {"ready": False, "instanceId": INSTANCE_ID})
            else:
                self.send_json(200, {"ready": True, "instanceId": INSTANCE_ID})
            return
        if self.path == "/llm/v1/capabilities":
            if not self.authorized("llm-capabilities"):
                return
            if "application/json" not in (self.headers.get("Accept") or ""):
                self.reject(406, "llm-capabilities", "Accept must include application/json")
                return
            count_method("llm/capabilities")
            self.send_json(
                200,
                {
                    "schemaVersion": 1,
                    "maxSchemaVersion": 1,
                    "chat": True,
                    "streaming": True,
                    "provider": {
                        "id": "fake-connected",
                        "name": "Fake Connected Provider",
                        "type": "openai_compatible",
                        "model": "wave4-e2e-model",
                        "streamingEnabled": True,
                    },
                },
            )
            return
        self.send_json(404, {"error": "not_found"})

    def do_DELETE(self) -> None:  # noqa: N802 - http.server API
        if self.path != "/mcp":
            self.send_json(404, {"error": "not_found"})
            return
        # EdtSupervisor's best-effort graceful shutdown targets the host base
        # with an unauthenticated DELETE and carries no MCP session headers.
        # Keep it distinct from the authenticated MCP session DELETE contract.
        if self.headers.get("Mcp-Session-Id") is None and self.headers.get("Authorization") is None:
            print("fake-edt-supervisor-shutdown-request", flush=True)
            self.send_response(204)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if not self.mcp_headers_valid("mcp-delete", session_required=True):
            return
        session_id = self.headers.get("Mcp-Session-Id")
        global DELETE_COUNT
        with STATE_LOCK:
            ACTIVE_SESSIONS.remove(session_id or "")
            DELETE_COUNT += 1
            delete_count = DELETE_COUNT
        count_method("DELETE")
        print(
            f"fake-edt-session-delete instance={INSTANCE_ID} count={delete_count}",
            flush=True,
        )
        self.send_response(204)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_POST(self) -> None:  # noqa: N802 - http.server API
        if self.path == "/mcp":
            self.handle_mcp()
            return
        if self.path == "/llm/v1/chat":
            self.handle_chat()
            return
        self.send_json(404, {"error": "not_found"})

    def handle_mcp(self) -> None:
        request = self.read_json("mcp")
        if request is None:
            return
        method = request.get("method")
        request_id = request.get("id")
        params = request.get("params")
        if (
            request.get("jsonrpc") != "2.0"
            or not isinstance(request_id, int)
            or isinstance(request_id, bool)
        ):
            self.reject(400, "mcp", "invalid JSON-RPC envelope")
            return
        if method == "initialize":
            if not self.mcp_headers_valid("mcp-initialize", session_required=False):
                return
            expected_params = {
                "protocolVersion": PROTOCOL,
                "capabilities": {},
                "clientInfo": {"name": "codepilot1c-cli", "version": "1.0.0"},
            }
            if params != expected_params:
                self.reject(400, "mcp-initialize", "initialize params do not match the CLI contract")
                return
            global SESSION_SEQUENCE
            with STATE_LOCK:
                SESSION_SEQUENCE += 1
                session_id = f"fake-{INSTANCE_ID}-{SESSION_SEQUENCE}"
                ACTIVE_SESSIONS.add(session_id)
            count_method(method)
            print(
                f"fake-edt-mcp-ok method=initialize session={SESSION_SEQUENCE}",
                flush=True,
            )
            self.send_json(
                200,
                {
                    "jsonrpc": "2.0",
                    "id": request_id,
                    "result": {
                        "protocolVersion": PROTOCOL,
                        "serverInfo": {"name": "fake-edt", "version": "wave4-e2e"},
                        "capabilities": {"tools": {"listChanged": False}},
                    },
                },
                session_id=session_id,
            )
            return

        if not self.mcp_headers_valid(f"mcp-{method}", session_required=True):
            return
        if method in ("tools/list", "ping") and params != {}:
            self.reject(400, f"mcp-{method}", "params must be an empty object")
            return
        count_method(str(method))
        print(f"fake-edt-mcp-ok method={method}", flush=True)
        if method == "tools/list":
            self.send_json(
                200,
                {
                    "jsonrpc": "2.0",
                    "id": request_id,
                    "result": {
                        "tools": [
                            {
                                "name": TOOL_NAME,
                                "description": TOOL_DESCRIPTION,
                                "inputSchema": TOOL_SCHEMA,
                                "annotations": {
                                    "title": "Wave 4 E2E echo",
                                    "destructiveHint": False,
                                    "readOnlyHint": False,
                                },
                                "_meta": {"codepilot1c/requiresConfirmation": True},
                            }
                        ]
                    },
                },
            )
        elif method == "tools/call":
            if params != {"name": TOOL_NAME, "arguments": TOOL_ARGUMENTS}:
                self.reject(400, "mcp-tools/call", "tool call name or arguments differ")
                return
            self.send_json(
                200,
                {
                    "jsonrpc": "2.0",
                    "id": request_id,
                    "result": {
                        "content": [{"type": "text", "text": TOOL_RESULT_TEXT}],
                        "structuredContent": {"echoed": TOOL_ARGUMENTS["value"]},
                        "isError": False,
                    },
                },
            )
        elif method == "ping":
            self.send_json(200, {"jsonrpc": "2.0", "id": request_id, "result": {}})
        else:
            self.send_json(
                200,
                {
                    "jsonrpc": "2.0",
                    "id": request_id,
                    "error": {"code": -32601, "message": "unknown method"},
                },
            )

    def handle_chat(self) -> None:
        if not self.authorized("llm-chat"):
            return
        if "text/event-stream" not in (self.headers.get("Accept") or ""):
            self.reject(406, "llm-chat", "Accept must include text/event-stream")
            return
        request = self.read_json("llm-chat")
        if request is None:
            return
        if request.get("schemaVersion") != 1:
            self.reject(400, "llm-chat", "schemaVersion must be 1")
            return
        messages = request.get("messages")
        tools = request.get("tools")
        if not isinstance(messages, list) or not isinstance(tools, list):
            self.reject(400, "llm-chat", "messages and tools must be arrays")
            return
        expected_tools = [
            {"name": TOOL_NAME, "description": TOOL_DESCRIPTION, "inputSchema": TOOL_SCHEMA}
        ]
        if tools != expected_tools:
            self.reject(400, "llm-chat", "annotated MCP tool was not brokered")
            return

        global CHAT_TURNS
        with STATE_LOCK:
            CHAT_TURNS += 1
            turn = CHAT_TURNS
        if turn == 1:
            if messages != [{"role": "user", "content": SHELL_PROMPT}]:
                self.reject(400, "llm-chat", "first turn does not contain the scripted prompt")
                return
            events = [
                (
                    "tool_calls",
                    {
                        "schemaVersion": 1,
                        "toolCalls": [
                            {
                                "id": TOOL_CALL_ID,
                                "name": TOOL_NAME,
                                "arguments": TOOL_ARGUMENTS,
                                "argumentsRepaired": False,
                            }
                        ],
                    },
                ),
                ("done", {"schemaVersion": 1, "finishReason": "tool_use"}),
            ]
        elif turn == 2:
            valid_history = (
                len(messages) == 3
                and all(isinstance(message, dict) for message in messages)
                and messages[0] == {"role": "user", "content": SHELL_PROMPT}
                and messages[1].get("role") == "assistant"
                and messages[1].get("toolCalls")
                == [{"id": TOOL_CALL_ID, "name": TOOL_NAME, "arguments": TOOL_ARGUMENTS}]
                and messages[2].get("role") == "tool"
                and messages[2].get("toolCallId") == TOOL_CALL_ID
                and TOOL_RESULT_TEXT in messages[2].get("content", "")
            )
            if not valid_history:
                self.reject(400, "llm-chat", "approved MCP result is absent from the second turn")
                return
            events = [
                ("delta", {"schemaVersion": 1, "text": FINAL_TEXT}),
                ("done", {"schemaVersion": 1, "finishReason": "stop"}),
            ]
        else:
            self.reject(409, "llm-chat", "unexpected extra broker turn")
            return
        count_method("llm/chat")
        print(f"fake-edt-llm-ok turn={turn}", flush=True)
        payload = "".join(
            f"event: {event}\ndata: {json.dumps(data, separators=(',', ':'))}\n\n"
            for event, data in events
        ).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)
        self.wfile.flush()


def main() -> int:
    if CONTRACT_ERRORS:
        print("fake-edt-contract-failure " + "; ".join(CONTRACT_ERRORS), flush=True)
        return 65
    if os.environ.get("CODEPILOT_E2E_TEST_BIND_FAILURE_ONCE") == "true":
        failure_marker = os.path.join(REGISTRY_DIR, ".fake-bind-failure-consumed")
        try:
            descriptor = os.open(failure_marker, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        except FileExistsError:
            pass
        else:
            os.close(descriptor)
            print(f"fake-edt-bind-failure port={PORT} simulated=once", flush=True)
            return 69
    try:
        server = Server(("127.0.0.1", PORT), Handler)
    except OSError:
        print(f"fake-edt-bind-failure port={PORT}", flush=True)
        return 69
    stop_requested = threading.Event()

    def stop(_signum: int, _frame: object) -> None:
        if stop_requested.is_set():
            return
        stop_requested.set()
        print(f"fake-edt-shutdown instance={INSTANCE_ID}", flush=True)
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    print(
        f"fake-edt-contract-ok application=com.codepilot1c.core.headless "
        f"workspace={WORKSPACE} port={PORT} bind=127.0.0.1 "
        f"instance={INSTANCE_ID} owner=cli registry={REGISTRY_DIR} argv=strict",
        flush=True,
    )
    print(f"fake-edt-ready instance={INSTANCE_ID} port={PORT}", flush=True)
    try:
        server.serve_forever(poll_interval=0.1)
    finally:
        with STATE_LOCK:
            active_count = len(ACTIVE_SESSIONS)
            delete_count = DELETE_COUNT
            method_counts = dict(METHOD_COUNTS)
            chat_turns = CHAT_TURNS
        print(
            "fake-edt-summary "
            f"active_sessions={active_count} deletes={delete_count} "
            f"chat_turns={chat_turns} methods={json.dumps(method_counts, sort_keys=True)}",
            flush=True,
        )
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
