package org.globsframework.mcp.model;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.GlobField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.mcp.jsonschema.JsonSchemaType;

/**
 * One entry of a {@code tools/list} result: what the model is told about a tool it may call.
 */
public class McpTool {
    public static final GlobType TYPE;

    public static final StringField name;

    public static final StringField title;

    public static final StringField description;

    public static final GlobField<JsonSchemaType> inputSchema;

    public static final GlobField<JsonSchemaType> outputSchema;

    static {
        GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("McpTool");
        name = typeBuilder.declareStringField("name");
        title = typeBuilder.declareStringField("title");
        description = typeBuilder.declareStringField("description");
        inputSchema = typeBuilder.declareGlobField("inputSchema", () -> JsonSchemaType.TYPE);
        outputSchema = typeBuilder.declareGlobField("outputSchema", () -> JsonSchemaType.TYPE);
        TYPE = typeBuilder.build();
    }
}
