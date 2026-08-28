package org.globsframework.mcp.jsonschema;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.annotations.Comment;
import org.globsframework.core.metamodel.fields.*;
import org.globsframework.json.annottations.JsonAsObject;
import org.globsframework.json.annottations.JsonValueAsField;

/**
 * A JSON Schema (draft 2020-12) node, described as a GlobType.
 * <p>
 * As for the OpenAPI model in globs-http, the JSON shape comes from globs-gson annotations rather than
 * from a serializer: {@code name} is a {@link JsonValueAsField} so that a schema sitting in an array
 * becomes a keyed entry of an object, and {@code properties}/{@code $defs} are {@link JsonAsObject}
 * arrays so they are written as objects rather than arrays.
 */
public class JsonSchemaType {
    public static final GlobType TYPE;

    /**
     * Not part of JSON Schema: the key under which this node is written when it belongs to
     * {@code properties} or {@code $defs}. Left unset on a root schema.
     */
    public static final StringField name;

    public static final StringField type;

    public static final StringField description;

    public static final StringField format;

    public static final GlobArrayField<JsonSchemaType> properties;

    public static final GlobField<JsonSchemaType> items;

    public static final StringArrayField required;

    public static final StringArrayField enumValues;

    public static final IntegerField maxLength;

    public static final GlobArrayField<JsonSchemaType> anyOf;

    public static final StringField ref;

    public static final GlobArrayField<JsonSchemaType> defs;

    static {
        GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("JsonSchema");
        name = typeBuilder.declareStringField("name", JsonValueAsField.UNIQUE_GLOB);
        type = typeBuilder.declareStringField("type", Comment.create("object, array, string, integer, number, boolean"));
        description = typeBuilder.declareStringField("description");
        format = typeBuilder.declareStringField("format", Comment.create("date, date-time, byte (base-64)"));
        properties = typeBuilder.declareGlobArrayField("properties", () -> JsonSchemaType.TYPE, JsonAsObject.UNIQUE_GLOB);
        items = typeBuilder.declareGlobField("items", () -> JsonSchemaType.TYPE);
        required = typeBuilder.declareStringArrayField("required");
        enumValues = typeBuilder.declareStringArrayField("enum");
        maxLength = typeBuilder.declareIntegerField("maxLength");
        anyOf = typeBuilder.declareGlobArrayField("anyOf", () -> JsonSchemaType.TYPE);
        ref = typeBuilder.declareStringField("$ref");
        defs = typeBuilder.declareGlobArrayField("$defs", () -> JsonSchemaType.TYPE, JsonAsObject.UNIQUE_GLOB);
        TYPE = typeBuilder.build();
    }
}
