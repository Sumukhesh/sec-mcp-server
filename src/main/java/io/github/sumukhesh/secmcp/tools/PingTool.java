package io.github.sumukhesh.secmcp.tools;

import java.time.Clock;
import java.time.Instant;

import io.github.sumukhesh.secmcp.model.PingResult;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Health-check tool. Lets a client confirm the server is wired up before calling EDGAR-backed tools.
 */
@Component
public class PingTool {

    static final int MAX_ECHO_LENGTH = 200;

    private final String serverVersion;
    private final Clock clock;

    public PingTool(@Value("${spring.ai.mcp.server.version}") String serverVersion, Clock clock) {
        this.serverVersion = serverVersion;
        this.clock = clock;
    }

    @McpTool(name = "ping",
            description = """
                    Check that the SEC filings server is running and reachable. \
                    Use this only to test connectivity; it does not contact the SEC. \
                    Optional parameter 'message' is echoed back (max 200 characters). \
                    Example: ping(message="hello") returns {"status":"ok","echo":"hello",...}.""",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public Mono<PingResult> ping(
            @McpToolParam(description = "Optional text to echo back, up to 200 characters.", required = false)
            String message) {
        String echo = message == null ? "" : message.strip();
        if (echo.length() > MAX_ECHO_LENGTH) {
            echo = echo.substring(0, MAX_ECHO_LENGTH);
        }
        return Mono.just(new PingResult("ok", serverVersion, echo, Instant.now(clock)));
    }
}
