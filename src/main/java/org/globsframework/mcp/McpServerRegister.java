package org.globsframework.mcp;

import org.globsframework.core.metamodel.GlobType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Declares the tools an MCP server exposes, the same way {@code HttpServerRegister} declares routes:
 * a {@link GlobType} for the input, a lambda for the behaviour, fluent extras for the rest.
 * <p>
 * Everything is walked once, in {@link #complete()} — the JSON Schema of every tool is built there and
 * the {@code tools/list} answer is serialized once and for all. Per-request work is a map lookup and a
 * decode against a known type.
 */
public class McpServerRegister {
    private static final Pattern TOOL_NAME = Pattern.compile("[a-zA-Z0-9_-]{1,128}");

    private final String serverName;
    private final String version;
    private final Map<String, ToolDeclaration> tools = new LinkedHashMap<>();
    private String title;
    private String instructions;

    public McpServerRegister(String serverName, String version) {
        this.serverName = serverName;
        this.version = version;
    }

    public McpServerRegister withTitle(String title) {
        this.title = title;
        return this;
    }

    /** Free-form guidance handed to the model at initialize time — how this server is meant to be used. */
    public McpServerRegister withInstructions(String instructions) {
        this.instructions = instructions;
        return this;
    }

    public ToolInfo registerTool(String name, GlobType inputType, McpToolHandler handler) {
        if (name == null || !TOOL_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(serverName + ": invalid tool name '" + name
                    + "' (expected " + TOOL_NAME.pattern() + ")");
        }
        if (tools.containsKey(name)) {
            throw new IllegalArgumentException(serverName + ": tool '" + name + "' is already registered");
        }
        if (inputType == null) {
            throw new IllegalArgumentException(serverName + ": tool '" + name + "' needs an input GlobType");
        }
        ToolDeclaration declaration = new ToolDeclaration(name, inputType, handler);
        tools.put(name, declaration);
        return declaration;
    }

    public McpDispatcher complete() {
        return new McpDispatcher(serverName, title, version, instructions, tools);
    }

    public interface ToolInfo {

        /** What the tool does — this is the single most important text the model reads. */
        ToolInfo comment(String comment);

        ToolInfo title(String title);

        /** Declaring it publishes an {@code outputSchema} and makes results structured. */
        ToolInfo declareReturnType(GlobType returnType);
    }

    static class ToolDeclaration implements ToolInfo {
        final String name;
        final GlobType inputType;
        final McpToolHandler handler;
        String comment;
        String title;
        GlobType returnType;

        ToolDeclaration(String name, GlobType inputType, McpToolHandler handler) {
            this.name = name;
            this.inputType = inputType;
            this.handler = handler;
        }

        public ToolInfo comment(String comment) {
            this.comment = comment;
            return this;
        }

        public ToolInfo title(String title) {
            this.title = title;
            return this;
        }

        public ToolInfo declareReturnType(GlobType returnType) {
            this.returnType = returnType;
            return this;
        }
    }
}
