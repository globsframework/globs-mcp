package org.globsframework.mcp.transport;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live {@code Mcp-Session-Id}s of an {@link McpHttpTransport}.
 * <p>
 * Nothing but an expiry is stored against an id: this dispatcher keeps no per-client state, so a session
 * exists only so the server can tell a client its context is gone (by answering 404) rather than
 * silently accepting requests from a client that believes it is still initialized.
 */
public class McpSessions {
    private final Map<String, Long> expiryById = new ConcurrentHashMap<>();
    private final long timeoutMs;

    public McpSessions(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public String open() {
        purge();
        String id = UUID.randomUUID().toString();
        expiryById.put(id, System.currentTimeMillis() + timeoutMs);
        return id;
    }

    /** @return true when the session is known and still alive, sliding its expiry forward. */
    public boolean touch(String id) {
        long now = System.currentTimeMillis();
        Long expiry = expiryById.get(id);
        if (expiry == null) {
            return false;
        }
        if (expiry < now) {
            expiryById.remove(id);
            return false;
        }
        expiryById.put(id, now + timeoutMs);
        return true;
    }

    public void close(String id) {
        if (id != null) {
            expiryById.remove(id);
        }
    }

    public int size() {
        return expiryById.size();
    }

    private void purge() {
        long now = System.currentTimeMillis();
        expiryById.entrySet().removeIf(entry -> entry.getValue() < now);
    }
}
