package org.globsframework.mcp.transport;

import org.globsframework.mcp.McpDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The MCP stdio transport: newline-delimited JSON-RPC on stdin/stdout. This is what a client such as
 * Claude Code or Claude Desktop launches as a subprocess.
 * <p>
 * <b>stdout belongs to the protocol.</b> Anything else written there corrupts the stream, so logging
 * must go to stderr — see {@code src/test/resources/log4j2.xml}, which does exactly that.
 * <p>
 * Messages are handled one at a time, in order, on the calling thread: {@code tools/call} blocks on the
 * handler's future. That matches how stdio clients drive a server and keeps the prototype honest; a
 * concurrent transport would pipeline here instead.
 */
public class McpStdioServer {
    private static final Logger log = LoggerFactory.getLogger(McpStdioServer.class);

    private final McpDispatcher dispatcher;

    public McpStdioServer(McpDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public void run() throws IOException {
        run(System.in, System.out);
    }

    public void run(InputStream in, OutputStream out) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            Optional<String> answer = dispatcher.dispatch(line);
            if (answer.isPresent()) {
                writer.write(answer.get());
                writer.write('\n');
                writer.flush();
            }
        }
        log.info("stdin closed, stopping");
    }
}
