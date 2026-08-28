package org.globsframework.mcp.model;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.GlobField;

public class ServerCapabilities {
    public static final GlobType TYPE;

    public static final GlobField<ToolsCapability> tools;

    static {
        GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("ServerCapabilities");
        tools = typeBuilder.declareGlobField("tools", () -> ToolsCapability.TYPE);
        TYPE = typeBuilder.build();
    }
}
