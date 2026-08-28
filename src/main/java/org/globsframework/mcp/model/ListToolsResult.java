package org.globsframework.mcp.model;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.GlobArrayField;
import org.globsframework.core.metamodel.fields.StringField;

public class ListToolsResult {
    public static final GlobType TYPE;

    public static final GlobArrayField<McpTool> tools;

    public static final StringField nextCursor;

    static {
        GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("ListToolsResult");
        tools = typeBuilder.declareGlobArrayField("tools", () -> McpTool.TYPE);
        nextCursor = typeBuilder.declareStringField("nextCursor");
        TYPE = typeBuilder.build();
    }
}
