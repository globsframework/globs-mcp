package org.globsframework.mcp.model;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.GlobField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.json.annottations.IsJsonContent;

/**
 * An outgoing JSON-RPC 2.0 message. Exactly one of {@code result} / {@code error} is set; the other
 * stays unset and globs-gson therefore does not write it.
 */
public class JsonRpcResponse {
    public static final GlobType TYPE;

    public static final StringField jsonrpc;

    public static final StringField id;

    public static final StringField result;

    public static final GlobField<JsonRpcError> error;

    static {
        GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("JsonRpcResponse");
        jsonrpc = typeBuilder.declareStringField("jsonrpc");
        id = typeBuilder.declareStringField("id", IsJsonContent.UNIQUE_GLOB);
        result = typeBuilder.declareStringField("result", IsJsonContent.UNIQUE_GLOB);
        error = typeBuilder.declareGlobField("error", () -> JsonRpcError.TYPE);
        TYPE = typeBuilder.build();
    }
}
