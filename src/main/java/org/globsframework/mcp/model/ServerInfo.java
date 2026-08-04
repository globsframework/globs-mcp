package org.globsframework.mcp.model;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.StringField;

public class ServerInfo {
    public static final GlobType TYPE;

    public static final StringField name;

    public static final StringField title;

    public static final StringField version;

    static {
        GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("ServerInfo");
        name = typeBuilder.declareStringField("name");
        title = typeBuilder.declareStringField("title");
        version = typeBuilder.declareStringField("version");
        TYPE = typeBuilder.build();
    }
}
