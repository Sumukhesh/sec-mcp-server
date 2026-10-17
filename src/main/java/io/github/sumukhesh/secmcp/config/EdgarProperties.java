package io.github.sumukhesh.secmcp.config;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for talking to SEC EDGAR. Validated at startup so a missing User-Agent fails fast
 * instead of surfacing later as a 403 from the SEC.
 *
 * @param userAgent             "Your Name your@email.com"; the SEC blocks requests without it
 * @param wwwBaseUrl            base for www.sec.gov (ticker file, filing archives)
 * @param dataBaseUrl           base for data.sec.gov (submissions, XBRL company facts)
 * @param rateLimitPerSecond    outbound requests per second; SEC allows 10, we stay below
 * @param requestTimeout        timeout for a single HTTP attempt
 * @param maxResponseSize       largest response body we will buffer (10-K HTML can exceed 10 MB)
 * @param retry                 backoff settings for 429 and 5xx responses
 * @param tickerRefreshInterval how long the in-memory ticker index is reused before re-fetching
 */
@Validated
@ConfigurationProperties("edgar")
public record EdgarProperties(
        @NotBlank(message = "edgar.user-agent is required (\"Your Name your@email.com\"). "
                + "Set EDGAR_USER_AGENT or add it to config/application.yml.")
        String userAgent,
        @NotNull URI wwwBaseUrl,
        @NotNull URI dataBaseUrl,
        @Min(1) @Max(9) int rateLimitPerSecond,
        @NotNull Duration requestTimeout,
        @NotNull Long maxResponseSize,
        @Valid @NotNull Retry retry,
        @NotNull Duration tickerRefreshInterval) {

    public record Retry(@Min(1) @Max(10) int maxAttempts, @NotNull Duration initialBackoff,
                        @NotNull Duration maxBackoff) {
    }

    @AssertTrue(message = "edgar.user-agent must look like \"Your Name your@email.com\" "
            + "(replace the REPLACE_ME placeholder in config/application.yml)")
    boolean isUserAgentWellFormed() {
        return userAgent == null || userAgent.isBlank()
                || (userAgent.contains("@") && userAgent.strip().contains(" ") && !userAgent.contains("REPLACE_ME"));
    }
}
