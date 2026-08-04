package org.globsframework.mcp.model;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.json.annottations.IsJsonContent;
import org.globsframework.json.annottations.IsJsonContent_;

/**
 * An incoming JSON-RPC 2.0 message.
 * <p>
 * {@code id} and {@code params} are raw JSON: an id is a string <em>or</em> a number and must be echoed
 * back byte-identical, and the shape of {@code params} depends on {@code method} — it is only decoded
 * once the method is known. A request with no {@code id} is a notification and gets no response.
 */
public class JsonRpcRequest {
    public static final GlobType TYPE;

    public static final StringField jsonrpc;

    @IsJsonContent_
    public static final StringField id;

    public static final StringField method;

    @IsJsonContent_
    public static final StringField params;

    static {
        GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("JsonRpcRequest");
        jsonrpc = typeBuilder.declareStringField("jsonrpc");
        id = typeBuilder.declareStringField("id", IsJsonContent.UNIQUE_GLOB);
        method = typeBuilder.declareStringField("method");
        params = typeBuilder.declareStringField("params", IsJsonContent.UNIQUE_GLOB);
        TYPE = typeBuilder.build();
    }
}
