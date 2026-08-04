package org.globsframework.mcp.http;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.annotations.Comment;
import org.globsframework.core.metamodel.fields.BooleanField;
import org.globsframework.core.metamodel.fields.DoubleField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.Glob;
import org.globsframework.http.HttpOp;
import org.globsframework.http.HttpServerRegister;
import org.globsframework.http.HttpTreatment;
import org.globsframework.json.GSonUtils;
import org.globsframework.mcp.McpDispatcher;
import org.globsframework.mcp.model.CallToolResult;
import org.globsframework.mcp.model.JsonRpcResponse;
import org.globsframework.mcp.model.TextContent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

public class HttpToMcpTest {

    /** An ordinary globs-http declaration — nothing here knows about MCP. */
    private HttpServerRegister httpServer() {
        HttpServerRegister register = new HttpServerRegister("shop/1.0");
        register.register("/product/{ref}", ProductPath.TYPE)
                .get(ProductQuery.TYPE, (HttpTreatment) (body, path, query) ->
                        CompletableFuture.completedFuture(Product.TYPE.instantiate()
                                .set(Product.ref, path.get(ProductPath.ref))
                                .set(Product.price, Boolean.TRUE.equals(query.get(ProductQuery.withVat)) ? 12.0 : 10.0)))
                .comment("Reads one product.")
                .declareReturnType(Product.TYPE);
        register.register("/product", null)
                .post(Product.TYPE, null, (HttpTreatment) (body, path, query) ->
                        CompletableFuture.completedFuture(Product.TYPE.instantiate()
                                .set(Product.ref, body.get(Product.ref))
                                .set(Product.price, body.get(Product.price))))
                .comment("Creates a product.");
        return register;
    }

    private McpDispatcher dispatcher() {
        return HttpToMcp.toMcp(httpServer(), "1.0").complete();
    }

    @Test
    public void urlsAndVerbsBecomeToolNames() {
        assertEquals("get_product_ref", HttpToMcp.toolName(HttpOp.get, "/product/{ref}"));
        assertEquals("post_order_id_lines", HttpToMcp.toolName(HttpOp.post, "/order/{id}/lines"));
        assertEquals("get_api", HttpToMcp.toolName(HttpOp.get, "/api"));
    }

    /**
     * The demo, in one assertion: an existing REST API describes itself well enough to become tools,
     * with path / query / body kept apart so their field names cannot collide.
     */
    @Test
    public void theRestApiBecomesToolsWithNoExtraDeclaration() {
        assertEquals(GSonUtils.normalize("""
                {"jsonrpc": "2.0", "id": 1, "result": {"tools": [
                  {
                    "name": "get_product_ref",
                    "description": "Reads one product. Returns Product.",
                    "inputSchema": {
                      "type": "object",
                      "properties": {
                        "path": {"description": "Parameters taken from the url /product/{ref}", "$ref": "#/$defs/ProductPath"},
                        "query": {"description": "Query string parameters", "$ref": "#/$defs/ProductQuery"}
                      },
                      "$defs": {
                        "ProductPath": {"type": "object", "properties": {"ref": {"type": "string"}}},
                        "ProductQuery": {"type": "object", "properties": {
                            "withVat": {"type": "boolean", "description": "Include VAT in the returned price"}}}
                      }
                    }
                  },
                  {
                    "name": "post_product",
                    "description": "Creates a product.",
                    "inputSchema": {
                      "type": "object",
                      "properties": {
                        "body": {"description": "Request body", "$ref": "#/$defs/Product"}
                      },
                      "$defs": {
                        "Product": {"type": "object", "properties": {
                            "ref": {"type": "string"}, "price": {"type": "number"}}}
                      }
                    }
                  }
                ]}}"""),
                GSonUtils.normalize(dispatch("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/list"}""")));
    }

    /** The handler runs in process — there is no server bound and no loopback call. */
    @Test
    public void callingTheToolRunsTheHttpHandler() {
        Glob result = callTool("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_product_ref",
                 "arguments":{"path":{"ref":"A-12"},"query":{"withVat":true}}}}""");

        assertNull(result.get(CallToolResult.isError));
        assertEquals("{\"ref\":\"A-12\",\"price\":12.0}",
                result.get(CallToolResult.content)[0].get(TextContent.text));
    }

    /** An omitted optional group is simply absent from the Glob, as an unset field. */
    @Test
    public void anOmittedQueryGroupFallsBackToTheOperationDefault() {
        Glob result = callTool("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_product_ref",
                 "arguments":{"path":{"ref":"B-7"}}}}""");

        assertEquals("{\"ref\":\"B-7\",\"price\":10.0}",
                result.get(CallToolResult.content)[0].get(TextContent.text));
    }

    @Test
    public void aPostBodyTravelsAsAGlob() {
        Glob result = callTool("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"post_product",
                 "arguments":{"body":{"ref":"C-1","price":3.5}}}}""");

        assertEquals("{\"ref\":\"C-1\",\"price\":3.5}",
                result.get(CallToolResult.content)[0].get(TextContent.text));
    }

    @Test
    public void urlsCanBeFilteredOut() {
        McpDispatcher filtered = HttpToMcp.toMcp(httpServer(), "1.0", url -> url.startsWith("/product/"))
                .complete();
        String tools = filtered.dispatch("""
                {"jsonrpc":"2.0","id":5,"method":"tools/list"}""").orElseThrow();
        assertTrue(tools.contains("get_product_ref"), tools);
        assertFalse(tools.contains("post_product"), tools);
    }

    private Glob callTool(String request) {
        Glob response = GSonUtils.decode(dispatch(request), JsonRpcResponse.TYPE);
        assertNull(response.get(JsonRpcResponse.error), () -> String.valueOf(response.get(JsonRpcResponse.error)));
        return GSonUtils.decode(response.get(JsonRpcResponse.result), CallToolResult.TYPE);
    }

    private String dispatch(String request) {
        return dispatcher().dispatch(request).orElseThrow();
    }

    public static class ProductPath {
        public static final GlobType TYPE;
        public static final StringField ref;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("ProductPath");
            ref = typeBuilder.declareStringField("ref");
            TYPE = typeBuilder.build();
        }
    }

    public static class ProductQuery {
        public static final GlobType TYPE;
        public static final BooleanField withVat;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("ProductQuery");
            withVat = typeBuilder.declareBooleanField("withVat",
                    Comment.TYPE.instantiate().set(Comment.VALUE, "Include VAT in the returned price"));
            TYPE = typeBuilder.build();
        }
    }

    public static class Product {
        public static final GlobType TYPE;
        public static final StringField ref;
        public static final DoubleField price;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("Product");
            ref = typeBuilder.declareStringField("ref");
            price = typeBuilder.declareDoubleField("price");
            TYPE = typeBuilder.build();
        }
    }
}
