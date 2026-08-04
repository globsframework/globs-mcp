package org.globsframework.mcp.http;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.annotations.Comment;
import org.globsframework.core.metamodel.fields.GlobField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.http.*;
import org.globsframework.mcp.McpServerRegister;
import org.globsframework.mcp.McpToolHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Exposes an existing {@link HttpServerRegister} as MCP tools — one tool per (url, verb).
 * <p>
 * The point of the module, in one class: the REST API was already described by GlobTypes, so the tool
 * definitions an LLM needs are already there. Nothing is re-declared, no annotations are added, no code
 * is generated. Handlers are invoked <em>in process</em>, not over the loopback: the same declaration
 * serves two protocols with one implementation.
 * <p>
 * Limits of the prototype, all of them structural rather than accidental:
 * <ul>
 *   <li>{@code getBin}/{@code postBin} operations are skipped — MCP tool results are not byte streams.</li>
 *   <li>No {@code outputSchema} is published. {@code declareReturnType} names the <em>element</em> type
 *       while the handler decides at runtime whether it returns one Glob or an array, so the schema
 *       could not be guaranteed to match the answer. The declared type is mentioned in the description
 *       instead. Tools registered directly on {@link McpServerRegister} do publish an output schema.</li>
 *   <li>Header types are not exposed: an LLM has no business setting transport headers. Operations that
 *       declare one receive an empty header Glob.</li>
 * </ul>
 */
public class HttpToMcp {
    private static final Logger log = LoggerFactory.getLogger(HttpToMcp.class);

    private HttpToMcp() {
    }

    public static McpServerRegister toMcp(HttpServerRegister httpServerRegister, String version) {
        return toMcp(httpServerRegister, version, url -> true);
    }

    public static McpServerRegister toMcp(HttpServerRegister httpServerRegister, String version, Predicate<String> urlFilter) {
        McpServerRegister register = new McpServerRegister(httpServerRegister.serverInfo, version);
        addTools(register, httpServerRegister, urlFilter);
        return register;
    }

    public static void addTools(McpServerRegister register, HttpServerRegister httpServerRegister, Predicate<String> urlFilter) {
        for (Map.Entry<String, HttpServerRegister.Verb> entry : httpServerRegister.verbMap.entrySet()) {
            if (!urlFilter.test(entry.getKey())) {
                continue;
            }
            HttpServerRegister.Verb verb = entry.getValue();
            for (HttpOperation operation : verb.operations) {
                if (operation instanceof DefaultHttpDataOperation) {
                    log.info("skipping {} {}: binary operations have no MCP tool equivalent",
                            operation.verb(), verb.url);
                    continue;
                }
                addTool(register, verb, operation);
            }
        }
    }

    private static void addTool(McpServerRegister register, HttpServerRegister.Verb verb, HttpOperation operation) {
        String toolName = toolName(operation.verb(), verb.url);

        // One synthetic input type per tool, keeping path / query / body apart so their field names
        // cannot collide.
        GlobTypeBuilder builder = GlobTypeBuilderFactory.create(toolName + "_input");
        GlobField<?> pathField = declareIfUsable(builder, "path", verb.pathParameters,
                "Parameters taken from the url " + verb.url);
        GlobField<?> queryField = declareIfUsable(builder, "query", operation.getQueryParamType(),
                "Query string parameters");
        GlobField<?> bodyField = declareIfUsable(builder, "body", operation.getBodyType(),
                "Request body");
        GlobType inputType = builder.build();

        McpToolHandler handler = input -> {
            Glob path = pathField == null ? null : input.get(pathField);
            Glob query = queryField == null ? null : input.get(queryField);
            Glob body = bodyField == null ? null : input.get(bodyField);
            // Nulls are what the server itself passes for an absent query string or header; the
            // operation substitutes its own empty Globs.
            CompletableFuture<HttpOutputData> future =
                    operation.consume(HttpInputData.fromGlob(body), path, query, null);
            return future == null ? CompletableFuture.completedFuture(null)
                    : future.thenApply(output -> asGlob(output, toolName));
        };

        register.registerTool(toolName, inputType, handler)
                .comment(description(verb, operation));
    }

    private static GlobField<?> declareIfUsable(GlobTypeBuilder builder, String name, GlobType type, String comment) {
        if (type == null || type.getFields().length == 0) {
            return null;
        }
        MutableGlob description = Comment.TYPE.instantiate().set(Comment.VALUE, comment);
        return builder.declareGlobField(name, () -> type, description);
    }

    private static Glob asGlob(HttpOutputData output, String toolName) {
        return switch (output) {
            case null -> null;
            case HttpOutputData.GlobHttpOutputData glob -> glob.getGlob();
            case HttpOutputData.GlobArrayHttpOutputData globs -> throw new UnsupportedOperationException(
                    toolName + " returned a Glob array; wrap it in a container type to expose it as a tool");
            case HttpOutputData.KnownSizeStreamHttpOutputData stream -> throw new UnsupportedOperationException(
                    toolName + " returned a stream, which has no MCP tool result equivalent");
        };
    }

    private static String description(HttpServerRegister.Verb verb, HttpOperation operation) {
        StringBuilder sb = new StringBuilder();
        if (operation.getComment() != null) {
            sb.append(operation.getComment());
        } else {
            sb.append("Calls ").append(operation.verb()).append(' ').append(verb.url);
        }
        if (operation.getReturnType() != null) {
            sb.append(" Returns ").append(operation.getReturnType().getName()).append('.');
        }
        return sb.toString();
    }

    /** {@code get} + {@code /order/{id}/lines} becomes {@code get_order_id_lines}. */
    static String toolName(HttpOp verb, String url) {
        StringBuilder sb = new StringBuilder(verb.name());
        boolean pendingSeparator = true;
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (pendingSeparator) {
                    sb.append('_');
                    pendingSeparator = false;
                }
                sb.append(c);
            } else {
                pendingSeparator = true;
            }
        }
        return sb.toString();
    }
}
