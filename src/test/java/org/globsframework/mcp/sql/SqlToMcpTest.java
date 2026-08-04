package org.globsframework.mcp.sql;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.annotations.KeyField;
import org.globsframework.core.metamodel.fields.*;
import org.globsframework.core.model.Glob;
import org.globsframework.json.GSonUtils;
import org.globsframework.mcp.McpDispatcher;
import org.globsframework.mcp.model.CallToolResult;
import org.globsframework.mcp.model.JsonRpcResponse;
import org.globsframework.mcp.model.TextContent;
import org.globsframework.sql.SqlConnection;
import org.globsframework.sql.drivers.jdbc.JdbcSqlService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SqlToMcpTest {
    private JdbcSqlService sqlService;
    private String tableName;
    private McpDispatcher dispatcher;

    @BeforeEach
    public void setUp() {
        sqlService = new JdbcSqlService("jdbc:hsqldb:.", "sa", "");
        SqlConnection db = sqlService.getDb();
        db.createTable(Product.TYPE);
        db.emptyTable(Product.TYPE);
        db.populate(List.of(
                product("A-12", "Espresso machine", 249.0, 3, true),
                product("B-07", "Coffee grinder", 89.5, 12, true),
                product("C-31", "Desk lamp", 39.0, 0, false),
                product("D-02", "Filter papers", 4.5, 240, true)));
        db.commitAndClose();

        tableName = sqlService.getTableName(Product.TYPE, true);
        // Nothing below knows the schema: it is read back from JDBC metadata.
        dispatcher = SqlToMcp.toMcp(sqlService, "catalog-db", "1.0", tableName).complete();
    }

    @AfterEach
    public void tearDown() {
        SqlConnection db = sqlService.getDb();
        db.emptyTable(Product.TYPE);
        db.commitAndClose();
    }

    /**
     * The demo: a database, no declaration, and the tools describe themselves. Note that the seven
     * filter types are shared — {@code StringFilter} is defined once and referenced by every text
     * column of every table.
     */
    @Test
    public void theTableSchemaBecomesTheToolSchema() {
        Glob tools = toolsList();
        String json = GSonUtils.encode(tools, false);

        assertTrue(json.contains("\"query_product\""), json);
        assertTrue(json.contains("\"count_product\""), json);

        // every column of the table is offered as a filter, typed by its column type
        assertTrue(json.contains("\"REF\":{\"description\":\"Conditions on column REF\",\"$ref\":\"#/$defs/StringFilter\"}"), json);
        assertTrue(json.contains("\"PRICE\":{\"description\":\"Conditions on column PRICE\",\"$ref\":\"#/$defs/DoubleFilter\"}"), json);
        assertTrue(json.contains("\"STOCK\":{\"description\":\"Conditions on column STOCK\",\"$ref\":\"#/$defs/IntegerFilter\"}"), json);
        assertTrue(json.contains("\"ACTIVE\":{\"description\":\"Conditions on column ACTIVE\",\"$ref\":\"#/$defs/BooleanFilter\"}"), json);

        // the shared filter vocabulary, defined once
        assertTrue(json.contains("\"StringFilter\":{\"type\":\"object\",\"description\":\"Conditions on a text column."), json);
        assertTrue(json.contains("\"greaterOrEqual\":{\"type\":\"number\"}"), json);

        // column names are enumerated for select/orderBy, so the model cannot invent one — and on
        // 'select', which is an array, the enum sits on the items
        assertTrue(json.contains("\"items\":{\"type\":\"string\",\"enum\":[\"REF\",\"LABEL\",\"PRICE\",\"STOCK\",\"ACTIVE\"]}"), json);
        assertTrue(json.contains("\"orderBy\":{\"type\":\"string\",\"enum\":[\"REF\",\"LABEL\",\"PRICE\",\"STOCK\",\"ACTIVE\"]}"), json);

        // rows are described by the extracted type itself
        assertTrue(json.contains("\"rows\":{\"type\":\"array\""), json);
    }

    @Test
    public void readingTheWholeTable() {
        Glob result = call("query_product", "{}");
        assertEquals(4, rows(result).size());
        assertNull(result.get(CallToolResult.isError));
    }

    /** A range condition on a numeric column, combined with an equality on a boolean one. */
    @Test
    public void filtersAreCombinedWithAnd() {
        Glob result = call("query_product", """
                {"where":{"PRICE":{"greaterOrEqual":50},"ACTIVE":{"equals":true}},"orderBy":"PRICE"}""");
        assertEquals(List.of("B-07", "A-12"), refs(result));
    }

    @Test
    public void textConditionsAreCaseInsensitive() {
        Glob result = call("query_product", """
                {"where":{"LABEL":{"contains":"COFFEE"}}}""");
        assertEquals(List.of("B-07"), refs(result));
    }

    @Test
    public void inAndNotEqualsWorkOnColumnsOfEveryType() {
        assertEquals(List.of("A-12", "C-31"),
                refs(call("query_product", """
                        {"where":{"REF":{"in":["A-12","C-31","Z-99"]}},"orderBy":"REF"}""")));
        assertEquals(List.of("A-12", "B-07", "D-02"),
                refs(call("query_product", """
                        {"where":{"STOCK":{"notEquals":0}},"orderBy":"REF"}""")));
    }

    @Test
    public void sortingDescendingAndProjectingColumns() {
        Glob result = call("query_product", """
                {"orderBy":"PRICE","descending":true,"select":["REF","PRICE"],"limit":2}""");
        assertEquals(List.of("A-12", "B-07"), refs(result));
        // only the projected columns come back; LABEL was not selected, so it is unset, not null
        assertEquals("[{\"REF\":\"A-12\",\"PRICE\":249.0},{\"REF\":\"B-07\",\"PRICE\":89.5}]",
                rows(result).toString());
    }

    /** One row beyond the limit is fetched so that "there is more" costs no second query. */
    @Test
    public void hittingTheLimitIsReported() {
        Glob truncated = call("query_product", """
                {"orderBy":"REF","limit":2}""");
        assertEquals(List.of("A-12", "B-07"), refs(truncated));
        assertTrue(structured(truncated).get("truncated").getAsBoolean());

        Glob complete = call("query_product", """
                {"orderBy":"REF","limit":4}""");
        assertFalse(structured(complete).get("truncated").getAsBoolean());
    }

    @Test
    public void offsetPagesThroughTheResult() {
        assertEquals(List.of("C-31", "D-02"),
                refs(call("query_product", """
                        {"orderBy":"REF","limit":2,"offset":2}""")));
    }

    @Test
    public void countingWithoutReturningRows() {
        Glob result = call("count_product", """
                {"where":{"ACTIVE":{"equals":true}}}""");
        assertEquals("{\"count\":3}", result.get(CallToolResult.content)[0].get(TextContent.text));
    }

    /**
     * The count is {@code COUNT(1)}, so it counts rows rather than non-null values of some column: it is
     * exact even on a table with no non-nullable column, and rows left null are still counted.
     */
    @Test
    public void countingIsExactOnANullableTable() {
        SqlConnection db = sqlService.getDb();
        db.createTable(Note.TYPE);
        db.emptyTable(Note.TYPE);
        db.populate(List.of(
                Note.TYPE.instantiate().set(Note.text, "something"),
                Note.TYPE.instantiate(),
                Note.TYPE.instantiate()));
        db.commitAndClose();
        try {
            McpDispatcher notes = SqlToMcp.toMcp(sqlService, "notes-db", "1.0",
                    sqlService.getTableName(Note.TYPE, true)).complete();

            String request = """
                    {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"count_note","arguments":{}}}""";
            Glob response = GSonUtils.decode(notes.dispatch(request).orElseThrow(), JsonRpcResponse.TYPE);
            assertNull(response.get(JsonRpcResponse.error));
            Glob result = GSonUtils.decode(response.get(JsonRpcResponse.result), CallToolResult.TYPE);
            assertEquals("{\"count\":3}", result.get(CallToolResult.content)[0].get(TextContent.text));
        } finally {
            SqlConnection cleanup = sqlService.getDb();
            cleanup.emptyTable(Note.TYPE);
            cleanup.commitAndClose();
        }
    }

    /**
     * The model has no way to express SQL: what it sends is a value bound to a PreparedStatement, so a
     * quoting payload is matched as a literal string rather than executed.
     */
    @Test
    public void aSqlPayloadIsJustAValue() {
        Glob result = call("query_product", """
                {"where":{"REF":{"equals":"x'; DROP TABLE PRODUCT; --"}}}""");
        assertNull(result.get(CallToolResult.isError));
        assertEquals(List.of(), refs(result));

        // the table is still there
        assertEquals(4, rows(call("query_product", "{}")).size());
    }

    /** An unknown column is refused by the tool rather than reaching the database. */
    @Test
    public void anUnknownColumnFailsTheToolCall() {
        Glob result = call("query_product", """
                {"orderBy":"NO_SUCH_COLUMN"}""");
        assertEquals(Boolean.TRUE, result.get(CallToolResult.isError));
        assertTrue(result.get(CallToolResult.content)[0].get(TextContent.text).contains("NO_SUCH_COLUMN"));
    }

    /**
     * The result type is synthesized from the table's own type, so the test reads the payload as JSON
     * rather than pretending to hold a handle on it — which is also what a real client does.
     */
    private JsonObject structured(Glob callResult) {
        return JsonParser.parseString(callResult.get(CallToolResult.structuredContent)).getAsJsonObject();
    }

    private JsonArray rows(Glob callResult) {
        return structured(callResult).getAsJsonArray("rows");
    }

    private List<String> refs(Glob callResult) {
        return rows(callResult).asList().stream()
                .map(row -> row.getAsJsonObject().get("REF").getAsString())
                .toList();
    }

    private Glob toolsList() {
        Glob response = GSonUtils.decode(
                dispatcher.dispatch("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/list"}""").orElseThrow(),
                JsonRpcResponse.TYPE);
        assertNull(response.get(JsonRpcResponse.error));
        return response;
    }

    private Glob call(String tool, String arguments) {
        String request = "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool + "\",\"arguments\":" + arguments + "}}";
        Glob response = GSonUtils.decode(dispatcher.dispatch(request).orElseThrow(), JsonRpcResponse.TYPE);
        assertNull(response.get(JsonRpcResponse.error),
                () -> String.valueOf(response.get(JsonRpcResponse.error)));
        return GSonUtils.decode(response.get(JsonRpcResponse.result), CallToolResult.TYPE);
    }

    private static Glob product(String ref, String label, double price, int stock, boolean active) {
        return Product.TYPE.instantiate()
                .set(Product.ref, ref)
                .set(Product.label, label)
                .set(Product.price, price)
                .set(Product.stock, stock)
                .set(Product.active, active);
    }

    /** No key, no non-nullable column: the old count tool could not be offered for this at all. */
    public static class Note {
        public static final GlobType TYPE;
        public static final StringField text;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("NOTE");
            text = typeBuilder.declareStringField("TEXT");
            TYPE = typeBuilder.build();
        }
    }

    public static class Product {
        public static final GlobType TYPE;
        public static final StringField ref;
        public static final StringField label;
        public static final DoubleField price;
        public static final IntegerField stock;
        public static final BooleanField active;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("PRODUCT");
            ref = typeBuilder.declareStringField("REF", KeyField.ZERO);
            label = typeBuilder.declareStringField("LABEL");
            price = typeBuilder.declareDoubleField("PRICE");
            stock = typeBuilder.declareIntegerField("STOCK");
            active = typeBuilder.declareBooleanField("ACTIVE");
            TYPE = typeBuilder.build();
        }
    }
}
