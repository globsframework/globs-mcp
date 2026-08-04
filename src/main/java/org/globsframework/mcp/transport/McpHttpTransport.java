package org.globsframework.mcp.transport;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.http.GlobHttpContent;
import org.globsframework.http.HttpInputData;
import org.globsframework.http.HttpOutputData;
import org.globsframework.http.HttpServerRegister;
import org.globsframework.http.model.HttpHeader;
import org.globsframework.mcp.McpDispatcher;
import org.globsframework.mcp.McpProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The MCP Streamable HTTP transport, served by globs-http.
 * <p>
 * One endpoint, three methods:
 * <ul>
 *   <li><b>POST</b> — one JSON-RPC message in, its answer back as {@code application/json}, or
 *       {@code 202 Accepted} with no body when the message was a notification.</li>
 *   <li><b>GET</b> — {@code 405}. The spec lets a server decline the server-to-client SSE stream, and
 *       this dispatcher has nothing to push: it only ever answers requests. Everything it does fits in
 *       the POST response, so opening an event stream would buy nothing.</li>
 *   <li><b>DELETE</b> — ends the session.</li>
 * </ul>
 * <p>
 * <b>Sessions.</b> {@code initialize} mints an {@code Mcp-Session-Id}; every later request must carry it
 * or get {@code 404}, which is the signal telling a client to reinitialize. Sessions expire after
 * {@link Options#sessionTimeoutMs}.
 * <p>
 * <b>Origin checking is on by default and rejects every request that carries an {@code Origin} header.</b>
 * A local MCP server has no browser clients, so any Origin means a web page is talking to it — the DNS
 * rebinding attack the MCP spec calls out by name. {@link Options#allowOrigins} opens it back up
 * explicitly. Bind the server to loopback as well; that is the bootstrap's job, not this class's.
 * <p>
 * Not implemented: SSE (so no resumability and no {@code Last-Event-ID}), JSON-RPC batching (removed
 * from the spec in 2025-06-18 anyway), and authorization — the MCP authorization spec is OAuth 2.1 over
 * HTTP and is a piece of work of its own.
 */
public class McpHttpTransport {
    private static final Logger log = LoggerFactory.getLogger(McpHttpTransport.class);

    public static final String DEFAULT_PATH = "/mcp";
    public static final String SESSION_HEADER = "Mcp-Session-Id";

    private McpHttpTransport() {
    }

    public static void register(HttpServerRegister httpServerRegister, McpDispatcher dispatcher) {
        register(httpServerRegister, DEFAULT_PATH, dispatcher, new Options());
    }

    public static void register(HttpServerRegister httpServerRegister, String path,
                                McpDispatcher dispatcher, Options options) {
        McpSessions sessions = new McpSessions(options.sessionTimeoutMs);
        HttpServerRegister.Verb verb = httpServerRegister.register(path, null);

        verb.postBin(null, McpRequestHeader.TYPE, (body, url, queryParameters, header) -> {
            Glob refused = check(header, options, sessions, true);
            if (refused != null) {
                return CompletableFuture.completedFuture(HttpOutputData.asGlob(refused));
            }
            String message = read(body);
            Optional<String> answer = dispatcher.dispatch(message);
            if (answer.isEmpty()) {
                // A notification gets no JSON-RPC answer; HTTP still needs a status.
                return CompletableFuture.completedFuture(HttpOutputData.asGlob(status(202)));
            }
            MutableGlob response = json(answer.get());
            if (isInitialize(message)) {
                // Only the answer to initialize carries the new session id.
                response.set(GlobHttpContent.headers,
                        new Glob[]{HttpHeader.create(SESSION_HEADER, sessions.open())});
            }
            return CompletableFuture.completedFuture(HttpOutputData.asGlob(response));
        }).comment("MCP Streamable HTTP endpoint");

        verb.get(null, McpRequestHeader.TYPE, (body, url, queryParameters, header) ->
                CompletableFuture.completedFuture(
                        text(405, "This server does not open a server-to-client stream; POST your requests.")));

        verb.delete(null, McpRequestHeader.TYPE, (body, url, queryParameters, header) -> {
            Glob refused = check(header, options, sessions, false);
            if (refused != null) {
                return CompletableFuture.completedFuture(refused);
            }
            sessions.close(header.get(McpRequestHeader.sessionId));
            return CompletableFuture.completedFuture(status(204));
        });
    }

    /** @return the response to send instead of handling the request, or null when it may proceed. */
    private static Glob check(Glob header, Options options, McpSessions sessions, boolean allowNewSession) {
        String origin = header.get(McpRequestHeader.origin);
        if (origin != null && !options.allowOrigins.contains(origin)) {
            log.warn("rejected request from origin {}", origin);
            return text(403, "Origin not allowed");
        }
        String sessionId = header.get(McpRequestHeader.sessionId);
        if (sessionId == null) {
            // The very first POST carries no session: it is the initialize call that creates one.
            return allowNewSession ? null : text(400, "Missing " + SESSION_HEADER);
        }
        if (!sessions.touch(sessionId)) {
            // 404 is what tells a client its session is gone and it must start a new one.
            return text(404, "Unknown or expired session");
        }
        return null;
    }

    private static boolean isInitialize(String message) {
        // Cheap and sufficient: only initialize may arrive without a session, and the answer to it is
        // the one that must carry the new session id.
        return message.contains("\"" + McpProtocol.INITIALIZE + "\"");
    }

    private static String read(HttpInputData body) throws Exception {
        if (body == null || body.isGlob()) {
            return "";
        }
        HttpInputData.SizedStream sized = body.asStream();
        if (sized == null) {
            return "";
        }
        try (InputStream in = sized.stream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static MutableGlob json(String payload) {
        return GlobHttpContent.TYPE.instantiate()
                .set(GlobHttpContent.content, payload.getBytes(StandardCharsets.UTF_8))
                .set(GlobHttpContent.mimeType, "application/json")
                .set(GlobHttpContent.charset, "UTF-8")
                .set(GlobHttpContent.statusCode, 200);
    }

    private static Glob status(int code) {
        return GlobHttpContent.TYPE.instantiate().set(GlobHttpContent.statusCode, code);
    }

    private static Glob text(int code, String message) {
        return GlobHttpContent.TYPE.instantiate()
                .set(GlobHttpContent.content, message.getBytes(StandardCharsets.UTF_8))
                .set(GlobHttpContent.mimeType, "text/plain")
                .set(GlobHttpContent.charset, "UTF-8")
                .set(GlobHttpContent.statusCode, code);
    }

    public static class Options {
        /** Origins allowed to reach the endpoint. Empty means "no browser may talk to this server". */
        public Set<String> allowOrigins = Set.of();

        public long sessionTimeoutMs = 30 * 60 * 1000L;

        public Options withAllowedOrigins(String... origins) {
            this.allowOrigins = Set.of(origins);
            return this;
        }

        public Options withSessionTimeoutMs(long sessionTimeoutMs) {
            this.sessionTimeoutMs = sessionTimeoutMs;
            return this;
        }
    }

    /**
     * The request headers the transport reads. Field names are the header names; globs-http matches them
     * ignoring case.
     */
    public static class McpRequestHeader {
        public static final GlobType TYPE;
        public static final StringField sessionId;
        public static final StringField origin;
        public static final StringField protocolVersion;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("McpRequestHeader");
            sessionId = typeBuilder.declareStringField(SESSION_HEADER);
            origin = typeBuilder.declareStringField("Origin");
            protocolVersion = typeBuilder.declareStringField("MCP-Protocol-Version");
            TYPE = typeBuilder.build();
        }
    }
}
