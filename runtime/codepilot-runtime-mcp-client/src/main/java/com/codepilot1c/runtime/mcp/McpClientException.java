package com.codepilot1c.runtime.mcp;

import com.google.gson.JsonElement;

/** Stable, machine-readable failure from the standalone MCP client. */
public class McpClientException extends RuntimeException {
    public enum Kind { STATE, TRANSPORT, HTTP, JSON_RPC, PROTOCOL, MALFORMED_RESPONSE }

    private static final int NO_RPC_CODE = Integer.MIN_VALUE;
    private final Kind kind;
    private final int httpStatus;
    private final int rpcCode;
    private final JsonElement rpcData;

    public McpClientException(Kind kind, String message) {
        this(kind, message, -1, NO_RPC_CODE, null, null);
    }

    public McpClientException(Kind kind, String message, Throwable cause) {
        this(kind, message, -1, NO_RPC_CODE, null, cause);
    }

    McpClientException(Kind kind, String message, int httpStatus, int rpcCode,
            JsonElement rpcData) {
        this(kind, message, httpStatus, rpcCode, rpcData, null);
    }

    private McpClientException(Kind kind, String message, int httpStatus, int rpcCode,
            JsonElement rpcData, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.rpcCode = rpcCode;
        this.rpcData = rpcData == null ? null : rpcData.deepCopy();
    }

    public Kind kind() { return kind; }
    public int httpStatus() { return httpStatus; }
    public boolean hasRpcError() { return rpcCode != NO_RPC_CODE; }
    public int rpcCode() {
        if (!hasRpcError()) throw new IllegalStateException("No JSON-RPC error code");
        return rpcCode;
    }
    public JsonElement rpcData() { return rpcData == null ? null : rpcData.deepCopy(); }
    boolean isUnsupportedProtocol() {
        String text = getMessage() == null ? "" : getMessage().toLowerCase(java.util.Locale.ROOT);
        return text.contains("unsupported") && text.contains("protocol");
    }
}
