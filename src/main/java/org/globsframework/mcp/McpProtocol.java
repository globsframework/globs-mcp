package org.globsframework.mcp;

import java.util.List;

public class McpProtocol {
    public static final String JSONRPC_VERSION = "2.0";

    /** Protocol revisions this server can speak, most recent first. */
    public static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");

    public static final String LATEST_PROTOCOL_VERSION = SUPPORTED_PROTOCOL_VERSIONS.get(0);

    public static final String INITIALIZE = "initialize";
    public static final String PING = "ping";
    public static final String TOOLS_LIST = "tools/list";
    public static final String TOOLS_CALL = "tools/call";

    // JSON-RPC 2.0 error codes
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    private McpProtocol() {
    }

    /** A JSON-RPC level fault, as opposed to a tool that ran and failed. */
    public static class ProtocolException extends RuntimeException {
        public final int code;

        public ProtocolException(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
