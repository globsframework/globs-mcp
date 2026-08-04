package org.globsframework.mcp;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.annotations.Comment;
import org.globsframework.core.metamodel.annotations.Required;
import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.model.Glob;
import org.globsframework.json.GSonUtils;
import org.globsframework.mcp.model.CallToolResult;
import org.globsframework.mcp.model.JsonRpcError;
import org.globsframework.mcp.model.JsonRpcResponse;
import org.globsframework.mcp.model.TextContent;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

public class McpDispatcherTest {

    private McpDispatcher dispatcher() {
        McpServerRegister register = new McpServerRegister("test-server", "1.0")
                .withInstructions("A calculator.");
        register.registerTool("add", Operands.TYPE, input -> CompletableFuture.completedFuture(
                        Sum.TYPE.instantiate().set(Sum.sum, input.get(Operands.a) + input.get(Operands.b))))
                .comment("Adds two integers.")
                .declareReturnType(Sum.TYPE);
        register.registerTool("boom", Operands.TYPE, input -> {
            throw new IllegalStateException("no can do");
        }).comment("Always fails.");
        return register.complete();
    }

    @Test
    public void initializeAgreesOnTheClientProtocolVersionWhenSupported() {
        assertEquals(GSonUtils.normalize("""
                {"jsonrpc": "2.0", "id": 1, "result": {
                   "protocolVersion": "2025-03-26",
                   "capabilities": {"tools": {"listChanged": false}},
                   "serverInfo": {"name": "test-server", "version": "1.0"},
                   "instructions": "A calculator."
                }}"""),
                GSonUtils.normalize(dispatch("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize",
                         "params":{"protocolVersion":"2025-03-26","capabilities":{},
                                   "clientInfo":{"name":"someone","version":"9"}}}""")));
    }

    @Test
    public void initializeFallsBackToOurLatestOnAnUnknownVersion() {
        String response = dispatch("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}""");
        assertTrue(response.contains("\"protocolVersion\":\"" + McpProtocol.LATEST_PROTOCOL_VERSION + "\""),
                response);
    }

    /** The whole point: the tool definitions the model reads are the GlobTypes, unmodified. */
    @Test
    public void toolsListPublishesTheGlobTypesAsJsonSchema() {
        assertEquals(GSonUtils.normalize("""
                {"jsonrpc": "2.0", "id": 2, "result": {"tools": [
                  {
                    "name": "add",
                    "description": "Adds two integers.",
                    "inputSchema": {
                      "type": "object",
                      "description": "A pair of integers",
                      "properties": {
                        "a": {"type": "integer", "description": "left operand"},
                        "b": {"type": "integer"}
                      },
                      "required": ["a", "b"]
                    },
                    "outputSchema": {
                      "type": "object",
                      "properties": {"sum": {"type": "integer"}}
                    }
                  },
                  {
                    "name": "boom",
                    "description": "Always fails.",
                    "inputSchema": {
                      "type": "object",
                      "description": "A pair of integers",
                      "properties": {
                        "a": {"type": "integer", "description": "left operand"},
                        "b": {"type": "integer"}
                      },
                      "required": ["a", "b"]
                    }
                  }
                ]}}"""),
                GSonUtils.normalize(dispatch("""
                        {"jsonrpc":"2.0","id":2,"method":"tools/list"}""")));
    }

    @Test
    public void callingAToolDecodesTheArgumentsIntoATypedGlob() {
        Glob response = GSonUtils.decode(dispatch("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                 "params":{"name":"add","arguments":{"a":3,"b":4}}}"""), JsonRpcResponse.TYPE);

        assertEquals("3", response.get(JsonRpcResponse.id));
        assertNull(response.get(JsonRpcResponse.error));

        Glob result = GSonUtils.decode(response.get(JsonRpcResponse.result), CallToolResult.TYPE);
        assertNull(result.get(CallToolResult.isError));
        assertEquals("{\"sum\":7}", result.get(CallToolResult.content)[0].get(TextContent.text));
        // structuredContent is only sent because the tool declared a return type
        assertEquals("{\"sum\":7}", result.get(CallToolResult.structuredContent));
    }

    /** A tool that ran and failed is a result the model can react to, not a JSON-RPC fault. */
    @Test
    public void aFailingToolAnswersWithIsError() {
        Glob response = GSonUtils.decode(dispatch("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call",
                 "params":{"name":"boom","arguments":{"a":1,"b":2}}}"""), JsonRpcResponse.TYPE);

        assertNull(response.get(JsonRpcResponse.error));
        Glob result = GSonUtils.decode(response.get(JsonRpcResponse.result), CallToolResult.TYPE);
        assertEquals(Boolean.TRUE, result.get(CallToolResult.isError));
        assertEquals("no can do", result.get(CallToolResult.content)[0].get(TextContent.text));
    }

    /** An unknown tool, on the other hand, is a protocol fault. */
    @Test
    public void anUnknownToolIsAJsonRpcError() {
        Glob response = GSonUtils.decode(dispatch("""
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"nope","arguments":{}}}"""),
                JsonRpcResponse.TYPE);

        assertNull(response.get(JsonRpcResponse.result));
        assertEquals(McpProtocol.INVALID_PARAMS, response.get(JsonRpcResponse.error).get(JsonRpcError.code));
        assertEquals("Unknown tool: nope", response.get(JsonRpcResponse.error).get(JsonRpcError.message));
    }

    @Test
    public void anUnknownMethodIsAJsonRpcError() {
        Glob response = GSonUtils.decode(dispatch("""
                {"jsonrpc":"2.0","id":6,"method":"resources/list"}"""), JsonRpcResponse.TYPE);
        assertEquals(McpProtocol.METHOD_NOT_FOUND, response.get(JsonRpcResponse.error).get(JsonRpcError.code));
    }

    @Test
    public void malformedJsonAnswersAParseErrorWithANullId() {
        String raw = dispatch("{ this is not json");
        // JSON-RPC requires a literal null id when the request could not be parsed. Reading it back
        // gives a field that is set and null, which is not the same as an absent id.
        assertTrue(raw.contains("\"id\":null"), raw);

        Glob response = GSonUtils.decode(raw, JsonRpcResponse.TYPE);
        assertTrue(response.isSet(JsonRpcResponse.id));
        assertNull(response.get(JsonRpcResponse.id));
        assertEquals(McpProtocol.PARSE_ERROR, response.get(JsonRpcResponse.error).get(JsonRpcError.code));
    }

    /** A message with no id is a notification, and JSON-RPC forbids answering it. */
    @Test
    public void notificationsGetNoAnswer() {
        assertEquals(Optional.empty(),
                dispatcher().dispatch("""
                        {"jsonrpc":"2.0","method":"notifications/initialized"}"""));
    }

    @Test
    public void aStringIdIsEchoedBackAsAString() {
        Glob response = GSonUtils.decode(dispatch("""
                {"jsonrpc":"2.0","id":"abc","method":"ping"}"""), JsonRpcResponse.TYPE);
        assertEquals("\"abc\"", response.get(JsonRpcResponse.id));
    }

    @Test
    public void duplicateToolNamesAreRejected() {
        McpServerRegister register = new McpServerRegister("test-server", "1.0");
        register.registerTool("add", Operands.TYPE, input -> null);
        assertThrows(IllegalArgumentException.class,
                () -> register.registerTool("add", Operands.TYPE, input -> null));
    }

    @Test
    public void invalidToolNamesAreRejected() {
        McpServerRegister register = new McpServerRegister("test-server", "1.0");
        assertThrows(IllegalArgumentException.class,
                () -> register.registerTool("not a valid name", Operands.TYPE, input -> null));
    }

    private String dispatch(String request) {
        return dispatcher().dispatch(request).orElseThrow();
    }

    public static class Operands {
        public static final GlobType TYPE;
        public static final IntegerField a;
        public static final IntegerField b;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("Operands");
            a = typeBuilder.declareIntegerField("a", Required.UNIQUE_GLOB,
                    Comment.TYPE.instantiate().set(Comment.VALUE, "left operand"));
            b = typeBuilder.declareIntegerField("b", Required.UNIQUE_GLOB);
            typeBuilder.addAnnotation(Comment.TYPE.instantiate().set(Comment.VALUE, "A pair of integers"));
            TYPE = typeBuilder.build();
        }
    }

    public static class Sum {
        public static final GlobType TYPE;
        public static final IntegerField sum;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("Sum");
            sum = typeBuilder.declareIntegerField("sum");
            TYPE = typeBuilder.build();
        }
    }
}
