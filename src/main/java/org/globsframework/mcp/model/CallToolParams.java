package org.globsframework.mcp.model;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.json.annottations.IsJsonContent;

/**
 * The {@code params} of a {@code tools/call} request. {@code arguments} stays raw JSON here: its shape
 * is the called tool's input GlobType, which is only known after {@code name} has been resolved.
 */
public class CallToolParams {
    public static final GlobType TYPE;

    public static final StringField name;

    public static final StringField arguments;

    static {
        GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("CallToolParams");
        name = typeBuilder.declareStringField("name");
        arguments = typeBuilder.declareStringField("arguments", IsJsonContent.UNIQUE_GLOB);
        TYPE = typeBuilder.build();
    }
}
