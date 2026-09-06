# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

The workspace-level `../CLAUDE.md` describes the globsframework ecosystem and the conventions shared by
all the sibling repos (annotation pairs, no reflection on the hot path, per-repo release cycles). Read it
too; what follows is specific to `globs-mcp`.

## What this module is

`org.globsframework:globs-mcp` — exposes `GlobType`s as Model Context Protocol tools. A `GlobType` is a
schema available at runtime, which is exactly what an LLM needs to call a tool, so the tool definition
*is* the type: no POJO, no annotation processor, no generated class.

Status: **prototype**, version 0.1-SNAPSHOT, not released anywhere. See the README for the list of what
is deliberately missing (stdio transport only, tools only, no auth).

## Build & test

Java 21. Dependencies are pinned as everywhere else in the workspace: `globs` 5.9.0, `globs-gson` 5.2.0,
`globs-http` 5.2-SNAPSHOT, `globs-sql` 5.2-SNAPSHOT. The last two are `<optional>` — only
`org.globsframework.mcp.http` and `org.globsframework.mcp.sql` need them, everything else works without.
`SqlToMcpTest` runs against an in-memory HSQLDB (`jdbc:hsqldb:.`), the driver being a test dependency
since globs-sql ships none.

```bash
mvn -o test
mvn -o test -Dtest=McpDispatcherTest#toolsListPublishesTheGlobTypesAsJsonSchema
```

Tests are **JUnit 5**. Test `GlobType`s are static nested classes at the bottom of each test file, built
with `GlobTypeBuilderFactory`. Assertions compare `GSonUtils.normalize(expected)` with
`GSonUtils.normalize(actual)` — note that `normalize` only reparses, it does **not** sort keys, so the
expected JSON must list attributes in `GlobType` field-declaration order.

## Architecture

### GlobType → JSON Schema

`jsonschema/GlobJsonSchema` walks the metamodel with a `FieldVisitor` and produces a `JsonSchemaType`
Glob. `JsonSchemaType` follows the same trick as the OpenAPI model in globs-http: the JSON shape comes
from globs-gson annotations, not from a serializer — `name` is a `JsonValueAsField` so a schema in an
array becomes a keyed entry, and `properties`/`$defs` are `JsonAsObject` arrays so they are written as
objects. Fields whose JSON name is not a Java identifier (`$ref`, `$defs`, `enum`) are declared with the
literal name and given a different Java name, as `OpenApiSchemaProperty.ref` does.

Nested types go into `$defs` and are referenced by `$ref`. **This is not an optimisation**: a GlobType
may reference itself, and inlining would not terminate. `Defs.nameOf` registers a type's name *before*
building its schema, which is what makes the recursive case resolve to a ref.

Annotations mapped: `Comment` → `description`, `Required` → the `required` array, `EnumAnnotation` →
`enum`, `MaxSize` → `maxLength` (note `MaxSize.KEY`, not `UNIQUE_KEY` — it is the one core annotation
that does not follow the usual naming).

On an **array field**, `description` stays on the array but `enum` and `maxLength` are written into
`items`: they constrain each element, and putting `enum` on the array would assert that the list itself
is one of the listed values.

The type mapping follows what globs-gson actually writes, so a document valid against the schema is a
document `GSonUtils.decode` accepts. `BigDecimal` is therefore a JSON `number` here, where the OpenAPI
generator in globs-http declares it a `string`.

### Protocol layer

`model/` holds the MCP and JSON-RPC messages as GlobTypes, one class per message, same style as
`globs-http`'s `openapi/model`. Three fields carry `IsJsonContent`, staying raw JSON on purpose:

- `JsonRpcRequest.id` — an id is a string *or* a number and must be echoed back byte-identical.
- `JsonRpcRequest.params` / `CallToolParams.arguments` — their shape depends on the method, or on which
  tool is being called, so they are only decoded once that is known.

The deserializer skips JSON attributes with no matching field (`GlobGSonDeserializer.read`), which is why
`InitializeParams` can declare only `protocolVersion` and ignore the client's capabilities block.

### Dispatch

`McpServerRegister` declares tools; `complete()` walks everything once, builds each tool's JSON Schema
and serializes the whole `tools/list` answer into a string held by the `McpDispatcher`. Per-request work
is a map lookup plus a decode against a known type — the usual "walk the metamodel at startup, plain
loop per request" pattern.

`McpDispatcher.dispatch(String)` returns `Optional<String>`: empty for a notification, which JSON-RPC
forbids answering. The error split matters and is spec-driven:

- unknown tool, malformed arguments, unknown method → **JSON-RPC error** (`ProtocolException`);
- a tool that ran and threw → **`CallToolResult` with `isError: true`**, because that is a result the
  model is meant to see and possibly recover from.

`structuredContent` is only sent when the tool declared a return type, so a client always has an
`outputSchema` to validate it against.

### Transports

Both sit behind `McpDispatcher` and carry strings; neither knows what a tool is.

`transport/McpStdioServer` — newline-delimited JSON on stdin/stdout.

`transport/McpHttpTransport` — Streamable HTTP, registered onto an `HttpServerRegister`. POST uses
`postBin` so the raw body is available (that is what lets a malformed payload become a proper `-32700`
instead of globs-http's 500 on a decode failure), and every response is a `GlobHttpContent` so the
transport controls status and headers. GET answers 405: the spec permits declining the server-to-client
SSE stream, and this dispatcher never pushes anything.

`transport/McpSessions` holds only an expiry per id — the dispatcher keeps no per-client state, so a
session exists purely so the server can answer 404 and make a client reinitialize instead of silently
serving one that believes it is still initialized.

Origin checking defaults to rejecting **every** request carrying an `Origin` header (DNS rebinding, which
the MCP spec names explicitly). `Options.withAllowedOrigins` is the opt-out.

This transport is why globs-http grew `GlobHttpContent.headers` and case-insensitive request-header
matching — see that repo's CLAUDE.md.

### Bridges

`http/HttpToMcp` turns an `HttpServerRegister` into tools, one per (url, verb). It calls
`operation.consume(...)` directly — in process, no loopback. It passes `null` for query and header
because that is what the real server passes when they are absent, and `DefaultHttpOperation` substitutes
its own empty Globs.

`sql/SqlToMcp` turns tables into tools. `SqlConnection.extractType(table)` builds the `GlobType` from
JDBC metadata once at startup; that type drives the filter vocabulary, the JSON Schema and the row
shape. Per table: `query_<table>` and `count_<table>`.

`sql/SqlFilter` holds **one filter GlobType per column data type**, not per column — every string column
of every table shares `StringFilter`. Since `GlobJsonSchema` emits nested types into `$defs`, a wide
schema still carries seven filter definitions. `filterTypeFor` returning null means "not filterable";
such a column is still selected and returned. `toConstraint` translates a filter Glob into a globs-sql
`Constraint`, so the model fills a structure and never writes SQL — injection is unrepresentable rather
than filtered.

Details that are decisions, not accidents:
- `top(limit + 1)` fetches one row past the limit, which is how `truncated` is computed without a second
  query.
- `count_<table>` uses `SelectBuilder.count()` (no argument), which renders `COUNT(1)` and therefore
  counts rows rather than non-null values of a column. It is exact on any table, including one with no
  key and no non-nullable column. The argument-taking `count(Field)` would undercount there.
- Field names of an extracted type are the **database column names** (upper-cased by the naming
  mapping), so that is what the model sees — `REF`, not `ref`.

## Gotchas

- **stdout is the stdio transport.** Anything written there that is not a JSON-RPC message corrupts the
  stream. Logging must go to stderr — `src/test/resources/log4j2.xml` does exactly that, and any sample
  or embedding must do the same. `DemoMcpServer` prints its HTTP banner to stderr for the same reason:
  the same binary also speaks stdio.
- `globs-http` **5.2-SNAPSHOT must be installed from source** (`mvn -o install` in `../globs-http`) for
  `McpHttpTransport` to compile: it needs `GlobHttpContent.headers`, added there for this module.
- `JsonRpcResponse.id` is set to the literal string `"null"` (raw JSON null) on a parse error, per
  JSON-RPC. Reading that back gives a field that is *set and null* — `isSet` is true, `get` returns Java
  null. `McpDispatcherTest.malformedJsonAnswersAParseErrorWithANullId` pins that behaviour.
- A `$ref` node here carries siblings (`description`). That is legal in draft 2020-12, which the module
  targets; it would have been ignored in draft-07.
- `operation.getQueryParamType()` returns `DefaultHttpOperation.EMPTY` (a zero-field type), never null —
  `HttpToMcp.declareIfUsable` tests the field count, not nullity.
- Tool names must match `[a-zA-Z0-9_-]{1,128}`; `McpServerRegister.registerTool` rejects anything else
  and rejects duplicates, which is what stops two urls sanitizing to the same name silently.
