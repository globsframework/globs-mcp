package org.globsframework.mcp.model;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.BooleanField;

public class ToolsCapability {
    public static final GlobType TYPE;

    /** This prototype declares a fixed tool set, so it never emits {@code notifications/tools/list_changed}. */
    public static final BooleanField listChanged;

    static {
        GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("ToolsCapability");
        listChanged = typeBuilder.declareBooleanField("listChanged");
        TYPE = typeBuilder.build();
    }
}
