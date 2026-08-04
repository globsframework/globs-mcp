package org.globsframework.mcp.model;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.StringField;

/**
 * Only the part of the {@code initialize} params this server acts on. The rest (client capabilities,
 * clientInfo) is skipped by the deserializer, which ignores JSON attributes with no matching field.
 */
public class InitializeParams {
    public static final GlobType TYPE;

    public static final StringField protocolVersion;

    static {
        GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("InitializeParams");
        protocolVersion = typeBuilder.declareStringField("protocolVersion");
        TYPE = typeBuilder.build();
    }
}
