#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Small local stand-in for a headless EDT MCP host.

This process is deliberately launched by the CLI supervisor.  It accepts the
same -D arguments that the real launcher receives, exposes only the readiness
and MCP lifecycle methods needed by the E2E harness, and records diagnostics
in the supervisor-owned log.
"""

from __future__ import annotations

import json
import os
import signal
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def option_value(prefix: str, default: str | None = None) -> str | None:
    for argument in sys.argv:
        if argument.startswith(prefix):
            return argument[len(prefix) :]
    return default


PORT = int(option_value("-Dcodepilot.mcp.host.http.port=", "0"))
INSTANCE_ID = option_value("-Dcodepilot.instance.id=", "unknown")
REGISTRY_DIR = option_value("-Dcodepilot.instance.registryDir=", "unknown")
WORKSPACE = option_value("__missing__", "")
try:
    WORKSPACE = sys.argv[sys.argv.index("-data") + 1]
except (ValueError, IndexError):
    pass
SESSION_ID = f"fake-{INSTANCE_ID}"
READY_DELAY = float(os.environ.get("CODEPILOT_FAKE_EDT_READY_DELAY", "0"))
READY_AT = time.monotonic() + max(0.0, READY_DELAY)
CONTRACT_ERRORS: list[str] = []
REQUIRED_ARGUMENTS = [
    ("-application", "com.codepilot1c.core.headless"),
    ("-Dcodepilot.mcp.enabled=", "true"),
    ("-Dcodepilot.mcp.host.enabled=", "true"),
    ("-Dcodepilot.mcp.host.http.enabled=", "true"),
    ("-Dcodepilot.mcp.host.http.bindAddress=", "127.0.0.1"),
    ("-Dcodepilot.instance.owner=", "cli"),
]
for argument, expected in REQUIRED_ARGUMENTS:
    if argument.startswith("-") and argument in ("-application",):
        try:
            actual = sys.argv[sys.argv.index(argument) + 1]
        except (ValueError, IndexError):
            actual = None
    else:
        actual = option_value(argument)
    if actual != expected:
        CONTRACT_ERRORS.append(f"{argument}{expected} (got {actual!r})")
if not WORKSPACE:
    CONTRACT_ERRORS.append("-data workspace")
if not INSTANCE_ID or INSTANCE_ID == "unknown":
    CONTRACT_ERRORS.append("-Dcodepilot.instance.id=<uuid>")
if not option_value("-Dcodepilot.mcp.host.http.port="):
    CONTRACT_ERRORS.append("-Dcodepilot.mcp.host.http.port=<port>")
if not option_value("-Dcodepilot.instance.registryDir="):
    CONTRACT_ERRORS.append("-Dcodepilot.instance.registryDir=<directory>")
SESSION_DELETE_SEEN = False


class Handler(BaseHTTPRequestHandler):
    server_version = "CodePilotE2EFakeEDT/1"

    def log_message(self, format: str, *args: object) -> None:
        print(f"fake-edt-http {self.address_string()} {format % args}", flush=True)

    def send_json(self, status: int, body: object, session: bool = False) -> None:
        payload = json.dumps(body, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        if session:
            self.send_header("Mcp-Session-Id", SESSION_ID)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self) -> None:  # noqa: N802 - http.server API
        if self.path == "/health/ready":
            if time.monotonic() < READY_AT:
                self.send_json(503, {"ready": False, "instanceId": INSTANCE_ID})
            else:
                self.send_json(200, {"ready": True, "instanceId": INSTANCE_ID})
        else:
            self.send_json(404, {"error": "not_found"})

    def do_DELETE(self) -> None:  # noqa: N802 - http.server API
        if self.path == "/mcp":
            global SESSION_DELETE_SEEN
            if self.headers.get("Mcp-Session-Id") == SESSION_ID:
                SESSION_DELETE_SEEN = True
                print(f"fake-edt-session-delete instance={INSTANCE_ID}", flush=True)
            self.send_response(204)
            self.send_header("Content-Length", "0")
            self.end_headers()
        else:
            self.send_json(404, {"error": "not_found"})

    def do_POST(self) -> None:  # noqa: N802 - http.server API
        if self.path != "/mcp":
            self.send_json(404, {"error": "not_found"})
            return
        length = int(self.headers.get("Content-Length", "0"))
        try:
            request = json.loads(self.rfile.read(length).decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            self.send_json(400, {"error": "invalid_json"})
            return
        method = request.get("method")
        request_id = request.get("id")
        if method == "initialize":
            if self.headers.get("Mcp-Session-Id"):
                self.send_json(400, {"error": "unexpected_session_header"})
                return
            self.send_json(
                200,
                {
                    "jsonrpc": "2.0",
                    "id": request_id,
                    "result": {
                        "protocolVersion": "2025-06-18",
                        "serverInfo": {"name": "fake-edt", "version": "e2e"},
                        "capabilities": {"tools": {}},
                    },
                },
                session=True,
            )
        elif method == "notifications/initialized":
            # The standalone CLI client does not currently send notifications,
            # but accepting this makes the fake useful with other local probes.
            self.send_json(200, {"jsonrpc": "2.0", "id": request_id, "result": {}})
        elif method == "tools/list":
            if self.headers.get("Mcp-Session-Id") != SESSION_ID:
                self.send_json(400, {"error": "missing_session_header"})
                return
            self.send_json(
                200,
                {
                    "jsonrpc": "2.0",
                    "id": request_id,
                    "result": {
                        "tools": [
                            {
                                "name": "e2e_echo",
                                "description": "Deterministic local E2E tool",
                                "inputSchema": {"type": "object"},
                            }
                        ]
                    },
                },
            )
        elif method == "ping":
            if self.headers.get("Mcp-Session-Id") != SESSION_ID:
                self.send_json(400, {"error": "missing_session_header"})
                return
            self.send_json(200, {"jsonrpc": "2.0", "id": request_id, "result": {}})
        else:
            self.send_json(
                200,
                {
                    "jsonrpc": "2.0",
                    "id": request_id,
                    "error": {"code": -32601, "message": f"unknown method: {method}"},
                },
            )


def main() -> int:
    if PORT < 1 or PORT > 65535:
        print(f"fake-edt-invalid-port port={PORT}", file=sys.stderr, flush=True)
        return 64
    if CONTRACT_ERRORS:
        print("fake-edt-contract-failure " + "; ".join(CONTRACT_ERRORS), flush=True)
        return 65
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
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
        f"instance={INSTANCE_ID} owner=cli registry={REGISTRY_DIR}",
        flush=True,
    )
    print(f"fake-edt-ready instance={INSTANCE_ID} port={PORT}", flush=True)
    try:
        server.serve_forever(poll_interval=0.1)
    finally:
        if not SESSION_DELETE_SEEN:
            print(f"fake-edt-session-delete-missing instance={INSTANCE_ID}", flush=True)
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
