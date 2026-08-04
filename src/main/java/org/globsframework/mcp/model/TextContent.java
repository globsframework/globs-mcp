package org.globsframework.mcp.model;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.Glob;

public class TextContent {
    public static final GlobType TYPE;

    public static final StringField type;

    public static final StringField text;

    static {
        GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("TextContent");
        type = typeBuilder.declareStringField("type");
        text = typeBuilder.declareStringField("text");
        TYPE = typeBuilder.build();
    }

    public static Glob text(String value) {
        return TYPE.instantiate()
                .set(type, "text")
                .set(text, value);
    }
}
