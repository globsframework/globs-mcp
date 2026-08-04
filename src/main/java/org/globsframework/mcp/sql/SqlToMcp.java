package org.globsframework.mcp.sql;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.annotations.Comment;
import org.globsframework.core.metamodel.annotations.EnumAnnotation;
import org.globsframework.core.metamodel.fields.*;
import org.globsframework.core.model.Glob;
import org.globsframework.core.streams.GlobStream;
import org.globsframework.core.streams.accessors.LongAccessor;
import org.globsframework.mcp.McpServerRegister;
import org.globsframework.mcp.McpToolHandler;
import org.globsframework.sql.SelectBuilder;
import org.globsframework.sql.SqlConnection;
import org.globsframework.sql.SqlService;
import org.globsframework.sql.constraints.Constraint;
import org.globsframework.sql.constraints.Constraints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Exposes tables of a relational database as MCP tools — point it at a database, get a typed server.
 * <p>
 * Nothing is declared: {@code SqlConnection.extractType(table)} builds a {@link GlobType} from JDBC
 * metadata at startup, and that type is at once the tool's filter vocabulary, its JSON Schema and the
 * shape of its rows. Adding a column to the table adds it to the tool on the next restart.
 * <p>
 * Per table, two tools: {@code query_<table>} and {@code count_<table>}. Both take the same
 * {@code where} object.
 * <p>
 * <b>The model never writes SQL.</b> It fills a typed filter structure ({@link SqlFilter}) which globs-sql
 * renders into a WHERE clause and binds through a PreparedStatement. There is no free-text SQL field to
 * escape, and the tool surface is read-only by construction: no insert, update, delete or DDL builder is
 * ever reachable from here.
 * <p>
 * Limits of the prototype:
 * <ul>
 *   <li>Tables are named explicitly. There is no "expose everything" mode — deciding what a model may
 *       read is not a default worth guessing.</li>
 *   <li>One connection per tool call, from {@link SqlService#getAutoCommitDb()}, closed in a finally.
 *       Fine for a stdio server driven one message at a time; a pool would go here.</li>
 *   <li>No joins and no aggregation beyond {@code count}: one tool reads one table.</li>
 *   <li>Columns whose kind has no filter type (blobs, the JSON-encoded composite fields) are returned
 *       but cannot be filtered on.</li>
 * </ul>
 */
public class SqlToMcp {
    private static final Logger log = LoggerFactory.getLogger(SqlToMcp.class);

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 1000;

    private SqlToMcp() {
    }

    public static McpServerRegister toMcp(SqlService sqlService, String serverName, String version, String... tables) {
        McpServerRegister register = new McpServerRegister(serverName, version);
        addTools(register, sqlService, tables);
        return register;
    }

    public static void addTools(McpServerRegister register, SqlService sqlService, String... tables) {
        SqlConnection db = sqlService.getAutoCommitDb();
        List<GlobType> tableTypes = new ArrayList<>();
        try {
            for (String table : tables) {
                // JDBC metadata → GlobType, once, at startup.
                tableTypes.add(db.extractType(table).extract());
            }
        } finally {
            db.commitAndClose();
        }
        for (GlobType tableType : tableTypes) {
            addTableTools(register, sqlService, tableType);
        }
    }

    private static void addTableTools(McpServerRegister register, SqlService sqlService, GlobType tableType) {
        String table = tableType.getName();
        String suffix = sanitize(table);
        String[] columnNames = Arrays.stream(tableType.getFields()).map(Field::getName).toArray(String[]::new);

        GlobType whereType = whereType(tableType);
        QueryInput input = queryInput(tableType, whereType, columnNames);
        QueryOutput output = queryOutput(tableType);

        register.registerTool("query_" + suffix, input.type,
                        queryHandler(sqlService, tableType, input, output))
                .comment("Reads rows of the " + table + " table. Every condition set in 'where' is combined "
                        + "with AND. Returns at most " + DEFAULT_LIMIT + " rows unless 'limit' says otherwise, "
                        + "and reports 'truncated' when more rows matched.")
                .declareReturnType(output.type);

        GlobTypeBuilder countBuilder = GlobTypeBuilderFactory.create(table + "_count_input");
        GlobField<?> countWhere = countBuilder.declareGlobField("where", () -> whereType,
                comment("Conditions the counted rows must match. Omit it to count the whole table."));
        GlobType countInputType = countBuilder.build();

        register.registerTool("count_" + suffix, countInputType,
                        countHandler(sqlService, tableType, countWhere))
                .comment("Counts the rows of " + table + " matching 'where', without returning them.")
                .declareReturnType(CountResult.TYPE);
    }

    private static GlobType whereType(GlobType tableType) {
        GlobTypeBuilder builder = GlobTypeBuilderFactory.create(tableType.getName() + "_where");
        for (Field field : tableType.getFields()) {
            GlobType filterType = SqlFilter.filterTypeFor(field);
            if (filterType == null) {
                log.debug("{}: {} is not filterable", tableType.getName(), field.getName());
                continue;
            }
            builder.declareGlobField(field.getName(), () -> filterType, columnComment(field));
        }
        builder.addAnnotation(comment("Conditions on the columns of " + tableType.getName()
                + ". Columns left out are not constrained."));
        return builder.build();
    }

    private static QueryInput queryInput(GlobType tableType, GlobType whereType, String[] columnNames) {
        GlobTypeBuilder builder = GlobTypeBuilderFactory.create(tableType.getName() + "_query_input");
        QueryInput input = new QueryInput();
        input.where = builder.declareGlobField("where", () -> whereType,
                comment("Conditions the rows must match. Omit it to read the whole table."));
        input.select = builder.declareStringArrayField("select",
                EnumAnnotation.create(columnNames),
                comment("Columns to return. Omit it to return them all."));
        input.orderBy = builder.declareStringField("orderBy", EnumAnnotation.create(columnNames));
        input.descending = builder.declareBooleanField("descending",
                comment("Sorts 'orderBy' from highest to lowest. Ignored without 'orderBy'."));
        input.limit = builder.declareIntegerField("limit",
                comment("Maximum number of rows to return. Defaults to " + DEFAULT_LIMIT
                        + ", capped at " + MAX_LIMIT + "."));
        input.offset = builder.declareIntegerField("offset",
                comment("Number of matching rows to skip, to page through a large result."));
        input.type = builder.build();
        return input;
    }

    private static QueryOutput queryOutput(GlobType tableType) {
        GlobTypeBuilder builder = GlobTypeBuilderFactory.create(tableType.getName() + "_query_result");
        QueryOutput output = new QueryOutput();
        output.rows = builder.declareGlobArrayField("rows", () -> tableType);
        output.returnedRows = builder.declareIntegerField("returnedRows");
        output.truncated = builder.declareBooleanField("truncated",
                comment("true when more rows matched than were returned; raise 'limit' or use 'offset'"));
        output.type = builder.build();
        return output;
    }

    private static McpToolHandler queryHandler(SqlService sqlService, GlobType tableType,
                                               QueryInput input, QueryOutput output) {
        return arguments -> {
            Constraint constraint = constraintOf(tableType, arguments.get(input.where));
            int limit = limitOf(arguments.get(input.limit));

            SqlConnection db = sqlService.getAutoCommitDb();
            try {
                SelectBuilder builder = constraint == null ? db.getQueryBuilder(tableType)
                        : db.getQueryBuilder(tableType, constraint);
                selectColumns(builder, tableType, arguments.get(input.select));
                orderBy(builder, tableType, arguments.get(input.orderBy),
                        Boolean.TRUE.equals(arguments.get(input.descending)));
                // One row more than asked: that is how "there is more" is detected without a second query.
                builder.top(limit + 1);
                Integer offset = arguments.get(input.offset);
                if (offset != null && offset > 0) {
                    builder.skip(offset);
                }

                List<Glob> rows = builder.getQuery().executeAsGlobs();
                boolean truncated = rows.size() > limit;
                if (truncated) {
                    rows = rows.subList(0, limit);
                }
                return CompletableFuture.completedFuture(output.type.instantiate()
                        .set(output.rows, rows.toArray(Glob[]::new))
                        .set(output.returnedRows, rows.size())
                        .set(output.truncated, truncated));
            } finally {
                db.commitAndClose();
            }
        };
    }

    private static McpToolHandler countHandler(SqlService sqlService, GlobType tableType,
                                               GlobField<?> whereField) {
        return arguments -> {
            Constraint constraint = constraintOf(tableType, arguments.get(whereField));
            SqlConnection db = sqlService.getAutoCommitDb();
            try {
                SelectBuilder builder = constraint == null ? db.getQueryBuilder(tableType)
                        : db.getQueryBuilder(tableType, constraint);
                // COUNT(1): counts rows, not non-null values of some column, so it needs no column and
                // is exact on any table.
                LongAccessor count = builder.count();
                // The query auto-closes its statement once the stream is exhausted.
                GlobStream stream = builder.getQuery().execute();
                long rows = stream.next() ? count.getLong() : 0L;
                return CompletableFuture.completedFuture(
                        CountResult.TYPE.instantiate().set(CountResult.count, rows));
            } finally {
                db.commitAndClose();
            }
        };
    }

    private static Constraint constraintOf(GlobType tableType, Glob where) {
        if (where == null) {
            return null;
        }
        List<Constraint> constraints = new ArrayList<>();
        for (Field whereField : where.getType().getFields()) {
            Glob filter = where.get((GlobField<?>) whereField);
            if (filter == null) {
                continue;
            }
            Constraint constraint = SqlFilter.toConstraint(tableType.getField(whereField.getName()), filter);
            if (constraint != null) {
                constraints.add(constraint);
            }
        }
        return switch (constraints.size()) {
            case 0 -> null;
            case 1 -> constraints.get(0);
            default -> Constraints.and(constraints.toArray(Constraint[]::new));
        };
    }

    private static void selectColumns(SelectBuilder builder, GlobType tableType, String[] selected) {
        if (selected == null || selected.length == 0) {
            builder.selectAll();
            return;
        }
        for (String column : selected) {
            Field field = tableType.findField(column);
            if (field == null) {
                throw new IllegalArgumentException("Unknown column '" + column + "' in "
                        + tableType.getName() + "; expected one of "
                        + Arrays.toString(Arrays.stream(tableType.getFields()).map(Field::getName).toArray()));
            }
            builder.select(field);
        }
    }

    private static void orderBy(SelectBuilder builder, GlobType tableType, String orderBy, boolean descending) {
        if (orderBy == null) {
            return;
        }
        Field field = tableType.findField(orderBy);
        if (field == null) {
            throw new IllegalArgumentException("Cannot sort on unknown column '" + orderBy
                    + "' in " + tableType.getName());
        }
        if (descending) {
            builder.orderDesc(field);
        } else {
            builder.orderAsc(field);
        }
    }

    private static int limitOf(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requested, MAX_LIMIT);
    }

    private static Glob columnComment(Field field) {
        Glob comment = field.findAnnotation(Comment.UNIQUE_KEY);
        return comment != null ? comment : comment("Conditions on column " + field.getName());
    }

    private static Glob comment(String text) {
        return Comment.TYPE.instantiate().set(Comment.VALUE, text);
    }

    static String sanitize(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '_' ? Character.toLowerCase(c) : '_');
        }
        return sb.toString();
    }

    public static class CountResult {
        public static final GlobType TYPE;
        public static final LongField count;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("CountResult");
            count = typeBuilder.declareLongField("count");
            TYPE = typeBuilder.build();
        }
    }

    private static class QueryInput {
        GlobType type;
        GlobField<?> where;
        StringArrayField select;
        StringField orderBy;
        BooleanField descending;
        IntegerField limit;
        IntegerField offset;
    }

    private static class QueryOutput {
        GlobType type;
        GlobArrayField<?> rows;
        IntegerField returnedRows;
        BooleanField truncated;
    }
}
