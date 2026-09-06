# globs-mcp

Expose `GlobType`s as [Model Context Protocol](https://modelcontextprotocol.io) tools.

An LLM that calls a tool needs a JSON Schema describing the arguments, and something that validates and
decodes what it sends back. In Java that normally means a POJO plus an annotation processor, or a
`Map<String, Object>` with no types at all. A `GlobType` is already a schema, available at runtime —
so the tool definition is the type, and nothing is generated.

> **Status: prototype.** The artifact is not published anywhere yet — build it locally (`mvn -o install`)
> and depend on `org.globsframework:globs-mcp:0.1-SNAPSHOT`. Java 21; `globs`, `globs-gson`, and
> `globs-http` / `globs-sql` for the two bridges.

## In two minutes

```java
McpServerRegister mcp = new McpServerRegister("catalog", "1.0");

mcp.registerTool("convert_price", ConversionRequest.TYPE, input ->
                CompletableFuture.completedFuture(ConversionResult.TYPE.instantiate()
                        .set(ConversionResult.amount, input.get(ConversionRequest.amount) * rate)))
        .comment("Converts an amount in euros to another currency.")
        .declareReturnType(ConversionResult.TYPE);

new McpStdioServer(mcp.complete()).run();
```

`input` is a `Glob` of the declared type — decoded, typed and validated. The JSON Schema the model sees
is derived from the same type: `Comment` becomes `description`, `Required` feeds `required`,
`EnumAnnotation` becomes `enum`, `MaxSize` becomes `maxLength`.

## Turning an existing REST API into an MCP server

If the application already exposes an API through `globs-http`, its routes are *already* described by
`GlobType`s and there is nothing left to declare:

```java
HttpServerRegister http = ...;                       // the API you already have
new McpStdioServer(HttpToMcp.toMcp(http, "1.0").complete()).run();
```

One tool per (url, verb), named `get_product_ref` for `GET /product/{ref}`. Path, query and body stay in
separate sub-objects so their field names cannot collide. Handlers run **in process** — the MCP server
does not call your HTTP server over the loopback, it calls the same lambda.

## Turning a database into an MCP server

Nothing is declared here either — `SqlConnection.extractType(table)` reads the schema back from JDBC
metadata, and that `GlobType` is at once the filter vocabulary, the JSON Schema and the row shape:

```java
SqlService sqlService = new JdbcSqlService(url, user, password);
new McpStdioServer(SqlToMcp.toMcp(sqlService, "catalog-db", "1.0", "PRODUCT", "ORDERS").complete()).run();
```

Per table you get `query_<table>` (filters, projection, sort, limit/offset) and `count_<table>`. Add a
column to the table and it appears in the tool on the next restart.

**The model never writes SQL.** It fills a typed filter structure — one filter type per column *data
type*, so `StringFilter` is defined once in `$defs` and referenced by every text column of every table:

```json
{"where": {"PRICE": {"lessOrEqual": 100}, "ACTIVE": {"equals": true}},
 "orderBy": "PRICE", "descending": true, "select": ["REF", "LABEL", "PRICE"]}
```

globs-sql renders that into a WHERE clause and binds the values through a PreparedStatement. There is no
free-text SQL field to escape, and the surface is read-only by construction: no insert, update, delete or
DDL builder is reachable from the tools.

Tables are named explicitly — there is no "expose everything" mode, because deciding what a model may
read is not a default worth guessing. Joins and aggregation beyond `count` are out of scope: one tool
reads one table.

## Transports

Two, behind the same `McpDispatcher` — they differ only in how bytes get in and out.

**stdio** (`McpStdioServer`), for a client that launches the server as a subprocess.

**Streamable HTTP** (`McpHttpTransport`), served by globs-http, for a server that runs somewhere else:

```java
HttpServerRegister http = new HttpServerRegister("my-mcp-server");
McpHttpTransport.register(http, mcp.complete());   // POST/GET/DELETE on /mcp
```

`initialize` mints an `Mcp-Session-Id`; later requests must carry it or get `404`, the signal telling a
client to reinitialize. `DELETE` ends the session. `GET` answers `405`: the spec lets a server decline the
server-to-client SSE stream, and this dispatcher has nothing to push — everything it does fits in the POST
response.

**Origin checking is on by default and rejects any request carrying an `Origin` header.** A local MCP
server has no browser clients, so an Origin means a web page is talking to it — the DNS rebinding attack
the spec calls out by name. `Options.withAllowedOrigins(...)` opens it up explicitly. Bind the server to
loopback too.

The demo runs on either: `DemoMcpServer` for stdio, `DemoMcpServer --http 8123` for HTTP.

## Running the demo

```bash
mvn -o dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=runtime
echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' \
  | java -cp target/classes:$(cat target/cp.txt) org.globsframework.mcp.sample.DemoMcpServer
```

The database demo needs a JDBC driver, which globs-sql deliberately does not ship, so it lives in the
test tree and runs off the test classpath:

```bash
mvn -o test-compile dependency:build-classpath -Dmdep.outputFile=target/cp-test.txt -Dmdep.includeScope=test
echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' \
  | java -cp target/classes:target/test-classes:$(cat target/cp-test.txt) \
         org.globsframework.mcp.sql.DemoSqlMcpServer
```

To wire either into an MCP client, declare that same `java -cp …` command as a stdio server.

## What works

`initialize` (with protocol version negotiation), `ping`, `tools/list`, `tools/call`, notifications, and
the JSON-RPC error codes. Protocol revisions 2024-11-05 through 2025-06-18.

A tool that runs and fails answers with `isError: true` — a result the model can read and react to.
An unknown tool or malformed arguments are JSON-RPC errors instead, as the spec requires.

## What this prototype does not do

- **No SSE**, on either transport: no resumability, no `Last-Event-ID`, no server-initiated messages.
- **No authorization.** MCP's is OAuth 2.1 over HTTP and is a piece of work of its own; a stdio server
  inherits the trust of the process that launched it, an HTTP one is only as safe as where you bind it.
- **No JSON-RPC batching** (removed from the spec in 2025-06-18 anyway).
- **No resources, prompts, completions, sampling or elicitation** — tools only.
- **No pagination** on `tools/list`, no `notifications/tools/list_changed`: the tool set is fixed at
  `complete()`.
- **Messages are handled one at a time** on the calling thread; `tools/call` blocks on the handler's
  future. Fine for stdio; over HTTP that blocks a globs-http reactor thread, so a real deployment wants
  `withExecutor` on the operation.
- **The `HttpToMcp` bridge publishes no `outputSchema`** (see the class javadoc for why), skips
  `getBin`/`postBin` operations, and does not expose header types.
- **`SqlToMcp` opens one connection per tool call** and does no joins beyond `count`; columns whose kind has no filter
  type (blobs, the JSON-encoded composite fields) are returned but cannot be filtered on.

`GlobJsonSchema` is independent of all of this: it turns a `GlobType` into a JSON Schema and is just as
useful for structured output or for any other tool-calling API.


## Building

```bash
mvn -o test
mvn -o install      # the artifact is not published; install it locally to depend on it
```

## License

Apache License 2.0 — see <https://www.apache.org/licenses/LICENSE-2.0.txt>.

## Links

- [Globs Framework](https://globsframework.org)
- [Model Context Protocol](https://modelcontextprotocol.io)
- [globs-http](https://github.com/globsframework/globs-http) · [globs-db](https://github.com/globsframework/globs-db) — the two bridges' sources
