package org.globsframework.mcp.jsonschema;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.annotations.Comment;
import org.globsframework.core.metamodel.annotations.EnumAnnotation;
import org.globsframework.core.metamodel.annotations.MaxSize;
import org.globsframework.core.metamodel.annotations.Required;
import org.globsframework.core.metamodel.fields.*;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.core.utils.Ref;
import org.globsframework.json.GSonUtils;

import java.util.*;

/**
 * Builds a JSON Schema (draft 2020-12) out of a {@link GlobType}, by walking the metamodel with a
 * {@link FieldVisitor} — no reflection.
 * <p>
 * The schema is what an LLM sees when it is offered a tool, so the annotations that carry intent are
 * mapped: {@link Comment} becomes {@code description}, {@link Required} feeds the {@code required}
 * array, {@link EnumAnnotation} becomes {@code enum} and {@link MaxSize} becomes {@code maxLength}.
 * <p>
 * Nested GlobTypes are emitted once into {@code $defs} and referenced by {@code $ref}. That is not an
 * optimisation: a GlobType may reference itself (directly or through a cycle), and inlining would not
 * terminate.
 * <p>
 * The mapping follows what globs-gson actually writes on the wire, so a document validating against the
 * schema is a document {@code GSonUtils.decode} accepts. Notably BigDecimal is a JSON number here,
 * where the OpenAPI generator in globs-http declares it a string.
 */
public class GlobJsonSchema {
    private static final String OBJECT = "object";
    private static final String ARRAY = "array";
    private static final String STRING = "string";
    private static final String INTEGER = "integer";
    private static final String NUMBER = "number";
    private static final String BOOLEAN = "boolean";
    private static final String DEFS_PREFIX = "#/$defs/";

    private GlobJsonSchema() {
    }

    /**
     * The root schema of {@code type}: an {@code object} whose properties are the type's fields, with
     * every referenced type collected under {@code $defs}.
     */
    public static Glob toSchema(GlobType type) {
        Defs defs = new Defs();
        MutableGlob root = objectSchema(type, defs);
        if (!defs.byName.isEmpty()) {
            root.set(JsonSchemaType.defs, defs.byName.values().toArray(Glob[]::new));
        }
        return root;
    }

    public static String toSchemaJson(GlobType type) {
        return GSonUtils.encode(toSchema(type), false);
    }

    private static MutableGlob objectSchema(GlobType type, Defs defs) {
        MutableGlob schema = JsonSchemaType.TYPE.instantiate()
                .set(JsonSchemaType.type, OBJECT);
        type.findOptAnnotation(Comment.UNIQUE_KEY)
                .map(comment -> comment.get(Comment.VALUE))
                .ifPresent(comment -> schema.set(JsonSchemaType.description, comment));

        List<Glob> properties = new ArrayList<>();
        List<String> required = new ArrayList<>();
        for (Field field : type.getFields()) {
            MutableGlob property = fieldSchema(field, defs);
            property.set(JsonSchemaType.name, field.getName());
            properties.add(property);
            if (field.hasAnnotation(Required.UNIQUE_KEY)) {
                required.add(field.getName());
            }
        }
        schema.set(JsonSchemaType.properties, properties.toArray(Glob[]::new));
        if (!required.isEmpty()) {
            schema.set(JsonSchemaType.required, required.toArray(String[]::new));
        }
        return schema;
    }

    private static MutableGlob fieldSchema(Field field, Defs defs) {
        final Ref<MutableGlob> result = new Ref<>();
        field.safeAccept(new FieldVisitor.AbstractWithErrorVisitor() {

            public void visitString(StringField field) {
                result.set(scalar(STRING, null));
            }

            public void visitStringArray(StringArrayField field) {
                result.set(array(scalar(STRING, null)));
            }

            public void visitInteger(IntegerField field) {
                result.set(scalar(INTEGER, null));
            }

            public void visitIntegerArray(IntegerArrayField field) {
                result.set(array(scalar(INTEGER, null)));
            }

            public void visitLong(LongField field) {
                result.set(scalar(INTEGER, null));
            }

            public void visitLongArray(LongArrayField field) {
                result.set(array(scalar(INTEGER, null)));
            }

            public void visitDouble(DoubleField field) {
                result.set(scalar(NUMBER, null));
            }

            public void visitDoubleArray(DoubleArrayField field) {
                result.set(array(scalar(NUMBER, null)));
            }

            public void visitBigDecimal(BigDecimalField field) {
                result.set(scalar(NUMBER, null));
            }

            public void visitBigDecimalArray(BigDecimalArrayField field) {
                result.set(array(scalar(NUMBER, null)));
            }

            public void visitBoolean(BooleanField field) {
                result.set(scalar(BOOLEAN, null));
            }

            public void visitBooleanArray(BooleanArrayField field) {
                result.set(array(scalar(BOOLEAN, null)));
            }

            public void visitDate(DateField field) {
                result.set(scalar(STRING, "date"));
            }

            public void visitDateTime(DateTimeField field) {
                result.set(scalar(STRING, "date-time"));
            }

            public void visitBytes(BytesField field) {
                result.set(scalar(STRING, "byte"));
            }

            public void visitGlob(GlobField<?> field) {
                result.set(defs.ref(field.getTargetType()));
            }

            public void visitGlobArray(GlobArrayField<?> field) {
                result.set(array(defs.ref(field.getTargetType())));
            }

            public void visitUnionGlob(GlobUnionField field) {
                result.set(anyOf(field.getTargetTypes(), defs));
            }

            public void visitUnionGlobArray(GlobArrayUnionField field) {
                result.set(array(anyOf(field.getTargetTypes(), defs)));
            }
        });

        MutableGlob schema = result.get();
        // The description documents the property; the value constraints constrain the value — which for
        // an array field is each item, not the array. Putting `enum` on the array would say "this list
        // must itself be one of these", which is never what the annotation means.
        MutableGlob valueSchema = ARRAY.equals(schema.get(JsonSchemaType.type))
                ? (MutableGlob) schema.get(JsonSchemaType.items)
                : schema;

        Glob comment = field.findAnnotation(Comment.UNIQUE_KEY);
        if (comment != null) {
            schema.set(JsonSchemaType.description, comment.get(Comment.VALUE));
        }
        EnumAnnotation.listEnums(field)
                .ifPresent(values -> valueSchema.set(JsonSchemaType.enumValues, values));
        Glob maxSize = field.findAnnotation(MaxSize.KEY);
        if (maxSize != null && maxSize.get(MaxSize.VALUE) != null && maxSize.get(MaxSize.VALUE) > 0) {
            valueSchema.set(JsonSchemaType.maxLength, maxSize.get(MaxSize.VALUE));
        }
        return schema;
    }

    private static MutableGlob scalar(String type, String format) {
        MutableGlob schema = JsonSchemaType.TYPE.instantiate().set(JsonSchemaType.type, type);
        if (format != null) {
            schema.set(JsonSchemaType.format, format);
        }
        return schema;
    }

    private static MutableGlob array(Glob items) {
        return JsonSchemaType.TYPE.instantiate()
                .set(JsonSchemaType.type, ARRAY)
                .set(JsonSchemaType.items, items);
    }

    private static MutableGlob anyOf(Collection<GlobType> targetTypes, Defs defs) {
        List<Glob> branches = new ArrayList<>();
        for (GlobType targetType : targetTypes) {
            branches.add(defs.ref(targetType));
        }
        return JsonSchemaType.TYPE.instantiate()
                .set(JsonSchemaType.anyOf, branches.toArray(Glob[]::new));
    }

    /**
     * The {@code $defs} section being accumulated. A type is registered under its name <em>before</em>
     * its own schema is built, so a recursive type resolves to a ref instead of recursing forever.
     */
    private static class Defs {
        private final Map<GlobType, String> names = new HashMap<>();
        private final Map<String, MutableGlob> byName = new LinkedHashMap<>();

        MutableGlob ref(GlobType type) {
            return JsonSchemaType.TYPE.instantiate()
                    .set(JsonSchemaType.ref, DEFS_PREFIX + nameOf(type));
        }

        private String nameOf(GlobType type) {
            String known = names.get(type);
            if (known != null) {
                return known;
            }
            String name = unusedName(sanitize(type.getName()));
            names.put(type, name);
            byName.put(name, null);         // reserve the slot: nameOf(type) now short-circuits
            MutableGlob schema = objectSchema(type, this);
            schema.set(JsonSchemaType.name, name);
            byName.put(name, schema);
            return name;
        }

        private String unusedName(String base) {
            if (!byName.containsKey(base)) {
                return base;
            }
            int index = 2;
            while (byName.containsKey(base + "_" + index)) {
                index++;
            }
            return base + "_" + index;
        }

        private static String sanitize(String typeName) {
            StringBuilder sb = new StringBuilder(typeName.length());
            for (int i = 0; i < typeName.length(); i++) {
                char c = typeName.charAt(i);
                sb.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' ? c : '_');
            }
            return sb.toString();
        }
    }
}
