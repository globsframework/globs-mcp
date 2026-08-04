package org.globsframework.mcp.sql;

import org.globsframework.mcp.transport.McpStdioServer;
import org.globsframework.sql.SqlConnection;
import org.globsframework.sql.drivers.jdbc.JdbcSqlService;

import java.util.List;

/**
 * "Point it at a database, get a typed MCP server", runnable.
 * <p>
 * It seeds an in-memory HSQLDB and then forgets everything it knows about the schema: the tools are
 * built from JDBC metadata alone. It lives in {@code src/test} because it needs a JDBC driver, and
 * globs-sql deliberately ships none.
 * <pre>
 * mvn -o test-compile dependency:build-classpath -Dmdep.outputFile=target/cp-test.txt -Dmdep.includeScope=test
 * java -cp target/classes:target/test-classes:$(cat target/cp-test.txt) \
 *      org.globsframework.mcp.sql.DemoSqlMcpServer
 * </pre>
 */
public class DemoSqlMcpServer {

    public static void main(String[] args) throws Exception {
        JdbcSqlService sqlService = new JdbcSqlService("jdbc:hsqldb:.", "sa", "");
        seed(sqlService);

        String table = sqlService.getTableName(SqlToMcpTest.Product.TYPE, true);
        new McpStdioServer(
                SqlToMcp.toMcp(sqlService, "catalog-db", "0.1", table)
                        .withTitle("Catalog database")
                        .withInstructions("Read-only access to the product catalog. "
                                + "Use count_product before query_product when you only need a number.")
                        .complete())
                .run();
    }

    private static void seed(JdbcSqlService sqlService) {
        SqlConnection db = sqlService.getDb();
        db.createTable(SqlToMcpTest.Product.TYPE);
        db.emptyTable(SqlToMcpTest.Product.TYPE);
        db.populate(List.of(
                row("A-12", "Espresso machine", 249.0, 3, true),
                row("B-07", "Coffee grinder", 89.5, 12, true),
                row("C-31", "Desk lamp", 39.0, 0, false),
                row("D-02", "Filter papers", 4.5, 240, true)));
        db.commitAndClose();
    }

    private static org.globsframework.core.model.Glob row(String ref, String label, double price,
                                                          int stock, boolean active) {
        return SqlToMcpTest.Product.TYPE.instantiate()
                .set(SqlToMcpTest.Product.ref, ref)
                .set(SqlToMcpTest.Product.label, label)
                .set(SqlToMcpTest.Product.price, price)
                .set(SqlToMcpTest.Product.stock, stock)
                .set(SqlToMcpTest.Product.active, active);
    }
}
