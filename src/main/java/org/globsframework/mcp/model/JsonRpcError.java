package org.globsframework.mcp.model;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.json.annottations.IsJsonContent;
import org.globsframework.json.annottations.IsJsonContent_;

public class JsonRpcError {
    public static final GlobType TYPE;

    public static final IntegerField code;

    public static final StringField message;

    @IsJsonContent_
    public static final StringField data;

    static {
        GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("JsonRpcError");
        code = typeBuilder.declareIntegerField("code");
        message = typeBuilder.declareStringField("message");
        data = typeBuilder.declareStringField("data", IsJsonContent.UNIQUE_GLOB);
        TYPE = typeBuilder.build();
    }
}
