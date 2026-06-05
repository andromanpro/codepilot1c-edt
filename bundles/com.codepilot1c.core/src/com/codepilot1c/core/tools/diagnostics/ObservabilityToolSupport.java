package com.codepilot1c.core.tools.diagnostics;

import com.codepilot1c.core.edt.observability.EdtLogLine;
import com.codepilot1c.core.edt.observability.InfobaseLockSnapshot;
import com.codepilot1c.core.edt.observability.OneCProcessSnapshot;
import com.codepilot1c.core.edt.observability.StandaloneServerStatus;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

final class ObservabilityToolSupport {

    private ObservabilityToolSupport() {
    }

    static JsonObject successEnvelope(String opId, String toolName) {
        JsonObject payload = new JsonObject();
        payload.addProperty("status", "ok"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.addProperty("op_id", opId); //$NON-NLS-1$
        payload.addProperty("tool", toolName); //$NON-NLS-1$
        payload.add("data", new JsonObject()); //$NON-NLS-1$
        return payload;
    }

    static ToolResult success(JsonObject payload) {
        return ToolResult.success(pretty(payload), ToolResult.ToolResultType.CODE);
    }

    static ToolResult failure(String opId, String toolName, String code, String message, boolean recoverable) {
        return ToolResult.failure(pretty(errorEnvelope(opId, toolName, code, message, recoverable)));
    }

    static JsonObject errorEnvelope(String opId, String toolName, String code, String message, boolean recoverable) {
        JsonObject payload = new JsonObject();
        payload.addProperty("status", "error"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.addProperty("op_id", opId); //$NON-NLS-1$
        payload.addProperty("tool", toolName); //$NON-NLS-1$
        payload.addProperty("error_code", code == null ? "INTERNAL_ERROR" : code); //$NON-NLS-1$ //$NON-NLS-2$
        payload.addProperty("message", message == null ? "" : message); //$NON-NLS-1$ //$NON-NLS-2$
        payload.addProperty("recoverable", recoverable); //$NON-NLS-1$
        payload.add("details", new JsonObject()); //$NON-NLS-1$
        return payload;
    }

    static JsonObject processJson(OneCProcessSnapshot snapshot, boolean includePorts) {
        JsonObject process = new JsonObject();
        process.addProperty("pid", snapshot.pid()); //$NON-NLS-1$
        process.addProperty("ppid", snapshot.ppid()); //$NON-NLS-1$
        process.addProperty("user", snapshot.user()); //$NON-NLS-1$
        process.addProperty("process_name", snapshot.processName()); //$NON-NLS-1$
        process.addProperty("command_line", snapshot.commandLine()); //$NON-NLS-1$
        process.addProperty("process_type", snapshot.processType()); //$NON-NLS-1$
        process.add("infobase_paths", strings(snapshot.infobasePaths())); //$NON-NLS-1$
        process.add("ports", includePorts ? integers(snapshot.ports()) : new JsonArray()); //$NON-NLS-1$
        process.add("children", longs(snapshot.children())); //$NON-NLS-1$
        return process;
    }

    static JsonObject lockJson(InfobaseLockSnapshot snapshot, boolean includeEvidence) {
        JsonObject lock = new JsonObject();
        lock.addProperty("input", snapshot.input()); //$NON-NLS-1$
        lock.addProperty("normalized_path", snapshot.normalizedPath()); //$NON-NLS-1$
        lock.add("inspected_paths", strings(snapshot.inspectedPaths())); //$NON-NLS-1$
        lock.addProperty("lock_kind", snapshot.lockKind()); //$NON-NLS-1$
        lock.addProperty("confidence", snapshot.confidence()); //$NON-NLS-1$
        lock.add("evidence", includeEvidence ? strings(snapshot.evidence()) : new JsonArray()); //$NON-NLS-1$
        lock.add("pids", longs(snapshot.pids())); //$NON-NLS-1$
        JsonArray processes = new JsonArray();
        for (OneCProcessSnapshot process : snapshot.processes()) {
            processes.add(processJson(process, true));
        }
        lock.add("processes", processes); //$NON-NLS-1$
        return lock;
    }

    static JsonObject standaloneStatusJson(StandaloneServerStatus status) {
        JsonObject server = new JsonObject();
        server.addProperty("server_name", status.serverName()); //$NON-NLS-1$
        server.addProperty("state", status.state()); //$NON-NLS-1$
        server.addProperty("pid", status.pid()); //$NON-NLS-1$
        server.add("ports", integers(status.ports())); //$NON-NLS-1$
        server.addProperty("config_path", status.configPath()); //$NON-NLS-1$
        server.addProperty("infobase_path", status.infobasePath()); //$NON-NLS-1$
        server.addProperty("debug_session", status.debugSession()); //$NON-NLS-1$
        server.addProperty("breakpoints_count", status.breakpointsCount()); //$NON-NLS-1$
        server.addProperty("designer_or_import_session", status.designerOrImportSession()); //$NON-NLS-1$
        JsonArray related = new JsonArray();
        for (OneCProcessSnapshot process : status.relatedProcesses()) {
            related.add(processJson(process, true));
        }
        server.add("related_processes", related); //$NON-NLS-1$
        JsonArray locks = new JsonArray();
        for (InfobaseLockSnapshot lock : status.locks()) {
            locks.add(lockJson(lock, false));
        }
        server.add("locks", locks); //$NON-NLS-1$
        server.add("last_errors", strings(status.lastErrors())); //$NON-NLS-1$
        return server;
    }

    static JsonObject logLineJson(EdtLogLine line) {
        JsonObject value = new JsonObject();
        value.addProperty("source", line.source()); //$NON-NLS-1$
        value.addProperty("path", line.path() == null ? "" : line.path().toString()); //$NON-NLS-1$ //$NON-NLS-2$
        value.addProperty("line_number", line.lineNumber()); //$NON-NLS-1$
        value.addProperty("text", line.text()); //$NON-NLS-1$
        value.addProperty("timestamp", line.timestamp()); //$NON-NLS-1$
        value.addProperty("level", line.level()); //$NON-NLS-1$
        value.addProperty("op_id", line.opId()); //$NON-NLS-1$
        value.addProperty("pid", line.pid()); //$NON-NLS-1$
        value.addProperty("infobase", line.infobase()); //$NON-NLS-1$
        return value;
    }

    static JsonArray strings(Iterable<?> values) {
        JsonArray array = new JsonArray();
        if (values != null) {
            for (Object value : values) {
                array.add(value == null ? "" : String.valueOf(value)); //$NON-NLS-1$
            }
        }
        return array;
    }

    static JsonArray integers(Iterable<Integer> values) {
        JsonArray array = new JsonArray();
        if (values != null) {
            for (Integer value : values) {
                array.add(value == null ? 0 : value.intValue());
            }
        }
        return array;
    }

    static JsonArray longs(Iterable<Long> values) {
        JsonArray array = new JsonArray();
        if (values != null) {
            for (Long value : values) {
                array.add(value == null ? 0L : value.longValue());
            }
        }
        return array;
    }

    static String pretty(JsonObject object) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(object);
    }
}
