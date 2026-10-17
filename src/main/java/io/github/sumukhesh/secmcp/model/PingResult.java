package io.github.sumukhesh.secmcp.model;

import java.time.Instant;

/**
 * Result of the {@code ping} tool.
 *
 * @param status        always {@code "ok"} when the server is reachable
 * @param serverVersion version of this MCP server
 * @param echo          the caller's message, echoed back (truncated)
 * @param timestamp     server time when the ping was handled
 */
public record PingResult(String status, String serverVersion, String echo, Instant timestamp) {
}
