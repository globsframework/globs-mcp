package org.globsframework.mcp.sql;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.annotations.Comment;
import org.globsframework.core.metamodel.fields.*;
import org.globsframework.core.model.Glob;
import org.globsframework.sql.constraints.Constraint;
import org.globsframework.sql.constraints.Constraints;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The filter vocabulary an LLM is given for one column, and its translation into a globs-sql
 * {@link Constraint}.
 * <p>
 * There is one filter GlobType <em>per column data type</em>, not per column: every string column of
 * every table shares {@link OfString}. Because {@code GlobJsonSchema} emits nested types into
 * {@code $defs}, a fifty-column schema still contains seven filter definitions, referenced fifty times.
 * <p>
 * The values are typed by construction — a date column offers a {@code date}-formatted field, an integer
 * column an {@code integer} one — so the model cannot put a string where a number goes, and it never
 * writes SQL: it fills a structure, and globs-sql renders the WHERE clause and binds the values through
 * a PreparedStatement. Injection is not filtered out here, it is unrepresentable.
 * <p>
 * A field kind with no filter type ({@code null} from {@link #filterTypeFor}) is simply not filterable;
 * it is still selected and returned.
 */
public class SqlFilter {

    private SqlFilter() {
    }

    /** @return the filter type to offer for {@code field}, or null when the kind cannot be filtered. */
    public static GlobType filterTypeFor(Field field) {
        return switch (field) {
            case StringField ignored -> OfString.TYPE;
            case IntegerField ignored -> OfInteger.TYPE;
            case LongField ignored -> OfLong.TYPE;
            case DoubleField ignored -> OfDouble.TYPE;
            case BooleanField ignored -> OfBoolean.TYPE;
            case DateField ignored -> OfDate.TYPE;
            case DateTimeField ignored -> OfDateTime.TYPE;
            default -> null;
        };
    }

    /**
     * @return the conjunction of everything set in {@code filter}, or null when nothing is set — an
     * empty filter object must not narrow the query.
     */
    public static Constraint toConstraint(Field field, Glob filter) {
        List<Constraint> constraints = new ArrayList<>();
        switch (field) {
            case StringField stringField -> {
                add(constraints, filter.get(OfString.equals), value -> Constraints.equal(stringField, value));
                add(constraints, filter.get(OfString.notEquals), value -> Constraints.notEqual(stringField, value));
                add(constraints, filter.get(OfString.contains), value -> Constraints.containsIgnoreCase(stringField, value));
                add(constraints, filter.get(OfString.startsWith), value -> Constraints.startWithIgnoreCase(stringField, value));
                addIn(constraints, field, filter.get(OfString.in));
                addIsNull(constraints, field, filter.get(OfString.isNull));
            }
            case IntegerField integerField -> {
                add(constraints, filter.get(OfInteger.equals), value -> Constraints.equal(integerField, value));
                add(constraints, filter.get(OfInteger.notEquals), value -> Constraints.notEqualUncheck(integerField, value));
                add(constraints, filter.get(OfInteger.greaterOrEqual), value -> Constraints.greater(integerField, value));
                add(constraints, filter.get(OfInteger.lessOrEqual), value -> Constraints.less(integerField, value));
                addIn(constraints, field, filter.get(OfInteger.in));
                addIsNull(constraints, field, filter.get(OfInteger.isNull));
            }
            case LongField longField -> {
                add(constraints, filter.get(OfLong.equals), value -> Constraints.equal(longField, value));
                add(constraints, filter.get(OfLong.notEquals), value -> Constraints.notEqualUncheck(longField, value));
                add(constraints, filter.get(OfLong.greaterOrEqual), value -> Constraints.greater(longField, value));
                add(constraints, filter.get(OfLong.lessOrEqual), value -> Constraints.less(longField, value));
                addIn(constraints, field, filter.get(OfLong.in));
                addIsNull(constraints, field, filter.get(OfLong.isNull));
            }
            case DoubleField doubleField -> {
                add(constraints, filter.get(OfDouble.equals), value -> Constraints.equalsObject(doubleField, value));
                add(constraints, filter.get(OfDouble.greaterOrEqual), value -> Constraints.greaterUnchecked(doubleField, value));
                add(constraints, filter.get(OfDouble.lessOrEqual), value -> Constraints.lessUncheck(doubleField, value));
                addIsNull(constraints, field, filter.get(OfDouble.isNull));
            }
            case BooleanField booleanField -> {
                add(constraints, filter.get(OfBoolean.equals), value -> Constraints.equal(booleanField, value));
                addIsNull(constraints, field, filter.get(OfBoolean.isNull));
            }
            case DateField dateField -> {
                add(constraints, filter.get(OfDate.equals), value -> Constraints.equals(dateField, value));
                add(constraints, filter.get(OfDate.greaterOrEqual), value -> Constraints.greaterUnchecked(dateField, value));
                add(constraints, filter.get(OfDate.lessOrEqual), value -> Constraints.lessUncheck(dateField, value));
                addIsNull(constraints, field, filter.get(OfDate.isNull));
            }
            case DateTimeField dateTimeField -> {
                add(constraints, filter.get(OfDateTime.equals), value -> Constraints.equalsObject(dateTimeField, value));
                add(constraints, filter.get(OfDateTime.greaterOrEqual), value -> Constraints.greaterUnchecked(dateTimeField, value));
                add(constraints, filter.get(OfDateTime.lessOrEqual), value -> Constraints.lessUncheck(dateTimeField, value));
                addIsNull(constraints, field, filter.get(OfDateTime.isNull));
            }
            default -> throw new IllegalArgumentException("No filter for " + field.getFullName());
        }
        return switch (constraints.size()) {
            case 0 -> null;
            case 1 -> constraints.get(0);
            default -> Constraints.and(constraints.toArray(Constraint[]::new));
        };
    }

    private static <T> void add(List<Constraint> constraints, T value, Function<T, Constraint> builder) {
        if (value != null) {
            constraints.add(builder.apply(value));
        }
    }

    private static void addIn(List<Constraint> constraints, Field field, String[] values) {
        if (values != null && values.length > 0) {
            constraints.add(Constraints.in(field, new HashSet<>(Arrays.asList(values))));
        }
    }

    private static void addIn(List<Constraint> constraints, Field field, int[] values) {
        if (values != null && values.length > 0) {
            constraints.add(Constraints.in(field, Arrays.stream(values).boxed().collect(Collectors.toSet())));
        }
    }

    private static void addIn(List<Constraint> constraints, Field field, long[] values) {
        if (values != null && values.length > 0) {
            constraints.add(Constraints.in(field, Arrays.stream(values).boxed().collect(Collectors.toSet())));
        }
    }

    private static void addIsNull(List<Constraint> constraints, Field field, Boolean isNull) {
        if (isNull != null) {
            constraints.add(isNull ? Constraints.isNull(field) : Constraints.isNotNull(field));
        }
    }

    private static Glob comment(String text) {
        return Comment.TYPE.instantiate().set(Comment.VALUE, text);
    }

    private static final Glob IS_NULL_COMMENT =
            comment("true keeps only rows where the column is null, false only rows where it is set");
    private static final Glob IN_COMMENT = comment("keeps rows whose value is one of these");

    public static class OfString {
        public static final GlobType TYPE;
        public static final StringField equals;
        public static final StringField notEquals;
        public static final StringField contains;
        public static final StringField startsWith;
        public static final StringArrayField in;
        public static final BooleanField isNull;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("StringFilter");
            equals = typeBuilder.declareStringField("equals");
            notEquals = typeBuilder.declareStringField("notEquals");
            contains = typeBuilder.declareStringField("contains", comment("case-insensitive substring match"));
            startsWith = typeBuilder.declareStringField("startsWith", comment("case-insensitive prefix match"));
            in = typeBuilder.declareStringArrayField("in", IN_COMMENT);
            isNull = typeBuilder.declareBooleanField("isNull", IS_NULL_COMMENT);
            typeBuilder.addAnnotation(comment("Conditions on a text column. Several set at once are combined with AND."));
            TYPE = typeBuilder.build();
        }
    }

    public static class OfInteger {
        public static final GlobType TYPE;
        public static final IntegerField equals;
        public static final IntegerField notEquals;
        public static final IntegerField greaterOrEqual;
        public static final IntegerField lessOrEqual;
        public static final IntegerArrayField in;
        public static final BooleanField isNull;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("IntegerFilter");
            equals = typeBuilder.declareIntegerField("equals");
            notEquals = typeBuilder.declareIntegerField("notEquals");
            greaterOrEqual = typeBuilder.declareIntegerField("greaterOrEqual");
            lessOrEqual = typeBuilder.declareIntegerField("lessOrEqual");
            in = typeBuilder.declareIntegerArrayField("in", IN_COMMENT);
            isNull = typeBuilder.declareBooleanField("isNull", IS_NULL_COMMENT);
            typeBuilder.addAnnotation(comment("Conditions on an integer column. Several set at once are combined with AND."));
            TYPE = typeBuilder.build();
        }
    }

    public static class OfLong {
        public static final GlobType TYPE;
        public static final LongField equals;
        public static final LongField notEquals;
        public static final LongField greaterOrEqual;
        public static final LongField lessOrEqual;
        public static final LongArrayField in;
        public static final BooleanField isNull;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("LongFilter");
            equals = typeBuilder.declareLongField("equals");
            notEquals = typeBuilder.declareLongField("notEquals");
            greaterOrEqual = typeBuilder.declareLongField("greaterOrEqual");
            lessOrEqual = typeBuilder.declareLongField("lessOrEqual");
            in = typeBuilder.declareLongArrayField("in", IN_COMMENT);
            isNull = typeBuilder.declareBooleanField("isNull", IS_NULL_COMMENT);
            typeBuilder.addAnnotation(comment("Conditions on a long column. Several set at once are combined with AND."));
            TYPE = typeBuilder.build();
        }
    }

    public static class OfDouble {
        public static final GlobType TYPE;
        public static final DoubleField equals;
        public static final DoubleField greaterOrEqual;
        public static final DoubleField lessOrEqual;
        public static final BooleanField isNull;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("DoubleFilter");
            equals = typeBuilder.declareDoubleField("equals");
            greaterOrEqual = typeBuilder.declareDoubleField("greaterOrEqual");
            lessOrEqual = typeBuilder.declareDoubleField("lessOrEqual");
            isNull = typeBuilder.declareBooleanField("isNull", IS_NULL_COMMENT);
            typeBuilder.addAnnotation(comment("Conditions on a decimal column. Several set at once are combined with AND."));
            TYPE = typeBuilder.build();
        }
    }

    public static class OfBoolean {
        public static final GlobType TYPE;
        public static final BooleanField equals;
        public static final BooleanField isNull;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("BooleanFilter");
            equals = typeBuilder.declareBooleanField("equals");
            isNull = typeBuilder.declareBooleanField("isNull", IS_NULL_COMMENT);
            typeBuilder.addAnnotation(comment("Conditions on a boolean column."));
            TYPE = typeBuilder.build();
        }
    }

    public static class OfDate {
        public static final GlobType TYPE;
        public static final DateField equals;
        public static final DateField greaterOrEqual;
        public static final DateField lessOrEqual;
        public static final BooleanField isNull;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("DateFilter");
            equals = typeBuilder.declareDateField("equals");
            greaterOrEqual = typeBuilder.declareDateField("greaterOrEqual", comment("on or after this date"));
            lessOrEqual = typeBuilder.declareDateField("lessOrEqual", comment("on or before this date"));
            isNull = typeBuilder.declareBooleanField("isNull", IS_NULL_COMMENT);
            typeBuilder.addAnnotation(comment("Conditions on a date column, as ISO-8601 dates (2026-01-31)."));
            TYPE = typeBuilder.build();
        }
    }

    public static class OfDateTime {
        public static final GlobType TYPE;
        public static final DateTimeField equals;
        public static final DateTimeField greaterOrEqual;
        public static final DateTimeField lessOrEqual;
        public static final BooleanField isNull;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("DateTimeFilter");
            equals = typeBuilder.declareDateTimeField("equals");
            greaterOrEqual = typeBuilder.declareDateTimeField("greaterOrEqual", comment("at or after this instant"));
            lessOrEqual = typeBuilder.declareDateTimeField("lessOrEqual", comment("at or before this instant"));
            isNull = typeBuilder.declareBooleanField("isNull", IS_NULL_COMMENT);
            typeBuilder.addAnnotation(comment("Conditions on a timestamp column, as ISO-8601 instants."));
            TYPE = typeBuilder.build();
        }
    }
}
