package org.globsframework.mcp.model;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.BooleanField;
import org.globsframework.core.metamodel.fields.GlobArrayField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.json.annottations.IsJsonContent;

/**
 * The result of {@code tools/call}. {@code content} is what the model reads; {@code structuredContent}
 * is the same value as JSON, sent only when the tool declared an output type, so a client can validate
 * it against {@code outputSchema}.
 * <p>
 * A tool that fails answers with {@code isError=true} rather than a JSON-RPC error: per the MCP spec the
 * failure is a result the model is meant to see and possibly recover from, not a protocol fault.
 */
public class CallToolResult {
    public static final GlobType TYPE;

    public static final GlobArrayField<TextContent> content;

    public static final StringField structuredContent;

    public static final BooleanField isError;

    static {
        GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("CallToolResult");
        content = typeBuilder.declareGlobArrayField("content", () -> TextContent.TYPE);
        structuredContent = typeBuilder.declareStringField("structuredContent", IsJsonContent.UNIQUE_GLOB);
        isError = typeBuilder.declareBooleanField("isError");
        TYPE = typeBuilder.build();
    }
}
