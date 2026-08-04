package org.globsframework.mcp;

import org.globsframework.core.model.Glob;

import java.util.concurrent.CompletableFuture;

/**
 * What a tool does. {@code input} is a Glob of the type declared at registration — already decoded and
 * typed, never a {@code Map<String, Object>}.
 * <p>
 * The returned Glob is serialized as the tool result; returning {@code null} means "no result".
 * Throwing (or failing the future) turns into a {@code CallToolResult} with {@code isError=true}, which
 * the model sees and can react to.
 */
public interface McpToolHandler {

    CompletableFuture<Glob> call(Glob input) throws Exception;
}
