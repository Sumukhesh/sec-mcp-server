package io.github.sumukhesh.secmcp.tools;

import io.github.sumukhesh.secmcp.edgar.EdgarException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides which errors a model may see. Input and SEC errors already carry friendly, actionable
 * messages; anything else is logged and replaced so internals never leak into a tool result.
 * Spring AI turns the thrown exception into a {@code CallToolResult} with {@code isError=true}.
 */
final class ToolErrors {

    private static final Logger log = LoggerFactory.getLogger(ToolErrors.class);

    private ToolErrors() {
    }

    static Throwable toFriendly(String tool, Throwable e) {
        if (e instanceof IllegalArgumentException || e instanceof EdgarException) {
            log.info("{} rejected: {}", tool, e.getMessage());
            return e;
        }
        log.error("{} failed unexpectedly", tool, e);
        return new IllegalStateException("Internal error in " + tool + ". Please retry; if it keeps failing, "
                + "the server logs have details.");
    }
}
