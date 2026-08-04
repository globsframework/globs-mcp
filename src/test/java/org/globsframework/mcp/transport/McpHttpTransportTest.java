package org.globsframework.mcp.transport;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.impl.bootstrap.AsyncServerBootstrap;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncServer;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.util.TimeValue;
import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.http.HttpServerRegister;
import org.globsframework.http.server.apache.GlobHttpApacheBuilder;
import org.globsframework.http.server.apache.Server;
import org.globsframework.mcp.McpServerRegister;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class McpHttpTransportTest {
    private HttpAsyncServer server;
    private int port;
    private CloseableHttpClient client;

    @BeforeEach
    public void setUp() {
        McpServerRegister mcp = new McpServerRegister("http-server", "1.0");
        mcp.registerTool("add", Operands.TYPE, input -> CompletableFuture.completedFuture(
                        Sum.TYPE.instantiate().set(Sum.sum, input.get(Operands.a) + input.get(Operands.b))))
                .comment("Adds two integers.")
                .declareReturnType(Sum.TYPE);

        HttpServerRegister http = new HttpServerRegister("mcp-over-http/1.0");
        McpHttpTransport.register(http, mcp.complete());

        Server instance = new GlobHttpApacheBuilder(http)
                .startAndWaitForStartup(AsyncServerBootstrap.bootstrap()
                        .setIOReactorConfig(IOReactorConfig.custom()
                                .setSoReuseAddress(true)
                                .setSoTimeout(15000, TimeUnit.MILLISECONDS)
                                .build()), 0);
        server = instance.getServer();
        port = instance.getPort();
        client = HttpClients.createDefault();
    }

    @AfterEach
    public void tearDown() throws Exception {
        client.close();
        server.initiateShutdown();
        server.awaitShutdown(TimeValue.of(10, TimeUnit.SECONDS));
    }

    /** A whole session over HTTP: initialize, use the returned session id, then end it. */
    @Test
    public void aFullSessionOverHttp() throws IOException {
        Response initialized = post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""", null);
        assertEquals(200, initialized.code);
        assertEquals("application/json", initialized.contentType);

        String session = initialized.header(McpHttpTransport.SESSION_HEADER);
        assertNotNull(session, "initialize must mint a session id");
        assertEquals("2025-06-18",
                initialized.json().getAsJsonObject("result").get("protocolVersion").getAsString());

        Response tools = post("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}""", session);
        assertEquals(200, tools.code);
        assertTrue(tools.body.contains("\"add\""), tools.body);

        Response called = post("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                 "params":{"name":"add","arguments":{"a":20,"b":22}}}""", session);
        assertEquals("{\"sum\":42}",
                called.json().getAsJsonObject("result").getAsJsonObject("structuredContent").toString());

        assertEquals(204, delete(session).code);
        // once deleted the session is gone, which is how a client learns to reinitialize
        assertEquals(404, post("""
                {"jsonrpc":"2.0","id":4,"method":"tools/list"}""", session).code);
    }

    /** A notification has no answer, so there is no body to send back — only a status. */
    @Test
    public void aNotificationIsAccepted() throws IOException {
        String session = post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""", null)
                .header(McpHttpTransport.SESSION_HEADER);

        Response response = post("""
                {"jsonrpc":"2.0","method":"notifications/initialized"}""", session);
        assertEquals(202, response.code);
        assertEquals("", response.body);
    }

    @Test
    public void anUnknownSessionIsRefused() throws IOException {
        assertEquals(404, post("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list"}""", "not-a-session").code);
    }

    @Test
    public void aDeleteWithoutSessionIsABadRequest() throws IOException {
        assertEquals(400, delete(null).code);
    }

    /**
     * DNS rebinding protection: a browser page reaching a local MCP server is exactly the attack the
     * spec calls out, so any Origin is refused unless explicitly allowed.
     */
    @Test
    public void aRequestCarryingAnOriginIsRefused() throws IOException {
        HttpPost post = new HttpPost("http://localhost:" + port + McpHttpTransport.DEFAULT_PATH);
        post.setEntity(new StringEntity("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""", ContentType.APPLICATION_JSON));
        post.addHeader("Origin", "http://evil.example");
        assertEquals(403, execute(post).code);
    }

    /** The server declines the server-to-client stream, which the spec allows. */
    @Test
    public void getIsNotAllowed() throws IOException {
        HttpGet get = new HttpGet("http://localhost:" + port + McpHttpTransport.DEFAULT_PATH);
        assertEquals(405, execute(get).code);
    }

    /** Malformed JSON still gets a well-formed JSON-RPC parse error, not an HTTP 500. */
    @Test
    public void malformedJsonIsAJsonRpcParseError() throws IOException {
        Response response = post("{ this is not json", null);
        assertEquals(200, response.code);
        assertEquals(-32700, response.json().getAsJsonObject("error").get("code").getAsInt());
    }

    private Response post(String body, String session) throws IOException {
        HttpPost post = new HttpPost("http://localhost:" + port + McpHttpTransport.DEFAULT_PATH);
        post.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        if (session != null) {
            // deliberately not the declared casing: header names are case-insensitive
            post.addHeader("mcp-session-id", session);
        }
        return execute(post);
    }

    private Response delete(String session) throws IOException {
        HttpDelete delete = new HttpDelete("http://localhost:" + port + McpHttpTransport.DEFAULT_PATH);
        if (session != null) {
            delete.addHeader(McpHttpTransport.SESSION_HEADER, session);
        }
        return execute(delete);
    }

    private Response execute(HttpUriRequestBase request) throws IOException {
        return client.execute(request, (ClassicHttpResponse response) -> {
            Response result = new Response();
            result.code = response.getCode();
            result.body = response.getEntity() == null ? ""
                    : EntityUtils.toString(response.getEntity());
            Header contentType = response.getFirstHeader("Content-Type");
            result.contentType = contentType == null ? null
                    : ContentType.parse(contentType.getValue()).getMimeType();
            result.headers = response.getHeaders();
            return result;
        });
    }

    private static class Response {
        int code;
        String body;
        String contentType;
        Header[] headers;

        String header(String name) {
            for (Header header : headers) {
                if (header.getName().equalsIgnoreCase(name)) {
                    return header.getValue();
                }
            }
            return null;
        }

        JsonObject json() {
            return JsonParser.parseString(body).getAsJsonObject();
        }
    }

    public static class Operands {
        public static final GlobType TYPE;
        public static final IntegerField a;
        public static final IntegerField b;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("HttpOperands");
            a = typeBuilder.declareIntegerField("a");
            b = typeBuilder.declareIntegerField("b");
            TYPE = typeBuilder.build();
        }
    }

    public static class Sum {
        public static final GlobType TYPE;
        public static final IntegerField sum;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("HttpSum");
            sum = typeBuilder.declareIntegerField("sum");
            TYPE = typeBuilder.build();
        }
    }
}
