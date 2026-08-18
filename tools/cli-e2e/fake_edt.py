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
import signal
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def option_value(prefix: str, default: str | None = None) -> str | None:
    for argument in sys.argv:
        if argument.startswith(prefix):
            return argument[len(prefix) :]
    return default


PORT = int(option_value("-Dcodepilot.mcp.host.http.port=", "0"))
INSTANCE_ID = option_value("-Dcodepilot.instance.id=", "unknown")
REGISTRY_DIR = option_value("-Dcodepilot.instance.registryDir=", "unknown")
SESSION_ID = f"fake-{INSTANCE_ID}"


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
            self.send_json(200, {"ready": True, "instanceId": INSTANCE_ID})
        else:
            self.send_json(404, {"error": "not_found"})

    def do_DELETE(self) -> None:  # noqa: N802 - http.server API
        if self.path == "/mcp":
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
        f"fake-edt-ready instance={INSTANCE_ID} port={PORT} registry={REGISTRY_DIR}",
        flush=True,
    )
    try:
        server.serve_forever(poll_interval=0.1)
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
