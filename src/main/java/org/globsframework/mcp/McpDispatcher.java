package org.globsframework.mcp;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.json.GSonUtils;
import org.globsframework.mcp.jsonschema.GlobJsonSchema;
import org.globsframework.mcp.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.globsframework.mcp.McpProtocol.*;

/**
 * Turns one JSON-RPC message into one JSON-RPC answer. Transport-agnostic: {@link org.globsframework.mcp.transport.McpStdioServer}
 * feeds it lines, but anything that can carry a string works.
 */
public class McpDispatcher {
    private static final Logger log = LoggerFactory.getLogger(McpDispatcher.class);

    private final String serverName;
    private final String title;
    private final String version;
    private final String instructions;
    private final Map<String, McpServerRegister.ToolDeclaration> tools;
    private final String toolsListResult;

    McpDispatcher(String serverName, String title, String version, String instructions,
                  Map<String, McpServerRegister.ToolDeclaration> tools) {
        this.serverName = serverName;
        this.title = title;
        this.version = version;
        this.instructions = instructions;
        this.tools = Map.copyOf(tools);
        this.toolsListResult = GSonUtils.encode(buildToolsList(tools.values()), false);
    }

    /**
     * @return the answer to send back, or empty for a notification — which JSON-RPC forbids answering.
     */
    public Optional<String> dispatch(String message) {
        Glob request;
        try {
            request = GSonUtils.decode(message, JsonRpcRequest.TYPE);
        } catch (Exception e) {
            return Optional.of(errorResponse(null, PARSE_ERROR, "Invalid JSON: " + e.getMessage()));
        }
        String id = request.get(JsonRpcRequest.id);
        String method = request.get(JsonRpcRequest.method);
        if (method == null) {
            return id == null ? Optional.empty()
                    : Optional.of(errorResponse(id, INVALID_REQUEST, "No method in request"));
        }
        if (id == null) {
            log.debug("{}: notification {}", serverName, method);
            return Optional.empty();
        }
        try {
            return Optional.of(switch (method) {
                case INITIALIZE -> okResponse(id, encode(initialize(request.get(JsonRpcRequest.params))));
                case PING -> okResponse(id, "{}");
                case TOOLS_LIST -> okResponse(id, toolsListResult);
                case TOOLS_CALL -> okResponse(id, encode(callTool(request.get(JsonRpcRequest.params))));
                default -> errorResponse(id, METHOD_NOT_FOUND, "Unknown method: " + method);
            });
        } catch (ProtocolException e) {
            return Optional.of(errorResponse(id, e.code, e.getMessage()));
        } catch (Exception e) {
            log.error("{}: {} failed", serverName, method, e);
            return Optional.of(errorResponse(id, INTERNAL_ERROR, String.valueOf(e.getMessage())));
        }
    }

    private Glob initialize(String paramsJson) {
        String requested = paramsJson == null ? null
                : GSonUtils.decode(paramsJson, InitializeParams.TYPE).get(InitializeParams.protocolVersion);
        // Answer in the client's revision when we speak it, otherwise propose ours and let it decide.
        String agreed = SUPPORTED_PROTOCOL_VERSIONS.contains(requested) ? requested : LATEST_PROTOCOL_VERSION;

        MutableGlob serverInfo = ServerInfo.TYPE.instantiate()
                .set(ServerInfo.name, serverName)
                .set(ServerInfo.version, version);
        if (title != null) {
            serverInfo.set(ServerInfo.title, title);
        }
        MutableGlob result = InitializeResult.TYPE.instantiate()
                .set(InitializeResult.protocolVersion, agreed)
                .set(InitializeResult.capabilities, ServerCapabilities.TYPE.instantiate()
                        .set(ServerCapabilities.tools, ToolsCapability.TYPE.instantiate()
                                .set(ToolsCapability.listChanged, false)))
                .set(InitializeResult.serverInfo, serverInfo);
        if (instructions != null) {
            result.set(InitializeResult.instructions, instructions);
        }
        return result;
    }

    private Glob callTool(String paramsJson) {
        if (paramsJson == null) {
            throw new ProtocolException(INVALID_PARAMS, "No params on " + TOOLS_CALL);
        }
        Glob params = GSonUtils.decode(paramsJson, CallToolParams.TYPE);
        String toolName = params.get(CallToolParams.name);
        McpServerRegister.ToolDeclaration tool = tools.get(toolName);
        if (tool == null) {
            throw new ProtocolException(INVALID_PARAMS, "Unknown tool: " + toolName);
        }

        String arguments = params.get(CallToolParams.arguments);
        Glob input;
        try {
            input = arguments == null ? tool.inputType.instantiate()
                    : GSonUtils.decode(arguments, tool.inputType);
        } catch (Exception e) {
            // A malformed argument object is a protocol fault, not a tool failure.
            throw new ProtocolException(INVALID_PARAMS,
                    "Invalid arguments for tool " + toolName + ": " + e.getMessage());
        }

        Glob output;
        try {
            CompletableFuture<Glob> call = tool.handler.call(input);
            output = call == null ? null : call.get();
        } catch (Exception e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            log.warn("{}: tool {} failed", serverName, toolName, cause);
            return failedResult(cause.getMessage() == null ? cause.toString() : cause.getMessage());
        }

        String json = output == null ? "null" : GSonUtils.encode(output, false);
        MutableGlob result = CallToolResult.TYPE.instantiate()
                .set(CallToolResult.content, new Glob[]{TextContent.text(json)});
        if (output != null && tool.returnType != null) {
            result.set(CallToolResult.structuredContent, json);
        }
        return result;
    }

    private static Glob failedResult(String message) {
        return CallToolResult.TYPE.instantiate()
                .set(CallToolResult.content, new Glob[]{TextContent.text(message)})
                .set(CallToolResult.isError, true);
    }

    private static Glob buildToolsList(Iterable<McpServerRegister.ToolDeclaration> declarations) {
        List<Glob> globs = new ArrayList<>();
        for (McpServerRegister.ToolDeclaration declaration : declarations) {
            MutableGlob tool = McpTool.TYPE.instantiate()
                    .set(McpTool.name, declaration.name)
                    .set(McpTool.inputSchema, GlobJsonSchema.toSchema(declaration.inputType));
            if (declaration.comment != null) {
                tool.set(McpTool.description, declaration.comment);
            }
            if (declaration.title != null) {
                tool.set(McpTool.title, declaration.title);
            }
            if (declaration.returnType != null) {
                tool.set(McpTool.outputSchema, GlobJsonSchema.toSchema(declaration.returnType));
            }
            globs.add(tool);
        }
        return ListToolsResult.TYPE.instantiate()
                .set(ListToolsResult.tools, globs.toArray(Glob[]::new));
    }

    private static String encode(Glob glob) {
        return GSonUtils.encode(glob, false);
    }

    private static String okResponse(String id, String resultJson) {
        return encode(JsonRpcResponse.TYPE.instantiate()
                .set(JsonRpcResponse.jsonrpc, JSONRPC_VERSION)
                .set(JsonRpcResponse.id, id)
                .set(JsonRpcResponse.result, resultJson));
    }

    private static String errorResponse(String id, int code, String message) {
        MutableGlob response = JsonRpcResponse.TYPE.instantiate()
                .set(JsonRpcResponse.jsonrpc, JSONRPC_VERSION)
                .set(JsonRpcResponse.error, JsonRpcError.TYPE.instantiate()
                        .set(JsonRpcError.code, code)
                        .set(JsonRpcError.message, message));
        // JSON-RPC wants a null id when the request could not even be parsed.
        response.set(JsonRpcResponse.id, id == null ? "null" : id);
        return encode(response);
    }
}
