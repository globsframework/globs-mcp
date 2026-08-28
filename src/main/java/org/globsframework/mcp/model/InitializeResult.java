package org.globsframework.mcp.model;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.GlobField;
import org.globsframework.core.metamodel.fields.StringField;

public class InitializeResult {
    public static final GlobType TYPE;

    public static final StringField protocolVersion;

    public static final GlobField<ServerCapabilities> capabilities;

    public static final GlobField<ServerInfo> serverInfo;

    public static final StringField instructions;

    static {
        GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("InitializeResult");
        protocolVersion = typeBuilder.declareStringField("protocolVersion");
        capabilities = typeBuilder.declareGlobField("capabilities", () -> ServerCapabilities.TYPE);
        serverInfo = typeBuilder.declareGlobField("serverInfo", () -> ServerInfo.TYPE);
        instructions = typeBuilder.declareStringField("instructions");
        TYPE = typeBuilder.build();
    }
}
