package io.github.sumukhesh.secmcp.edgar;

import java.net.URI;
import java.util.Map;
import java.util.regex.Pattern;

import io.github.sumukhesh.secmcp.config.EdgarProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;
import tools.jackson.databind.JsonNode;

/**
 * The only way this server talks to the SEC. Every request:
 * <ul>
 *   <li>carries the configured User-Agent (the SEC blocks anonymous clients),</li>
 *   <li>waits for a token from the shared rate limiter, including each retry,</li>
 *   <li>is retried with exponential backoff and jitter on 429, 5xx and network errors.</li>
 * </ul>
 * URLs are built only from validated identifiers (CIK, accession number, document file name);
 * callers can never supply a URL.
 */
@Component
public class EdgarClient {

    private static final Logger log = LoggerFactory.getLogger(EdgarClient.class);

    /** Document names come from SEC data, but they still end up in a URL path, so be strict. */
    private static final Pattern SAFE_FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,199}");

    private static final ParameterizedTypeReference<Map<String, CompanyTickerEntry>> TICKERS_TYPE =
            new ParameterizedTypeReference<>() { };

    private final EdgarProperties props;
    private final TokenBucketRateLimiter rateLimiter;
    private final WebClient webClient;

    @Autowired
    public EdgarClient(EdgarProperties props) {
        this(props, new TokenBucketRateLimiter(props.rateLimitPerSecond()));
    }

    EdgarClient(EdgarProperties props, TokenBucketRateLimiter rateLimiter) {
        this.props = props;
        this.rateLimiter = rateLimiter;
        HttpClient httpClient = HttpClient.create()
                .compress(true) // SEC fair-access guidance asks clients to accept gzip
                .responseTimeout(props.requestTimeout());
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.USER_AGENT, props.userAgent())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(Math.toIntExact(props.maxResponseSize())))
                .build();
    }

    /** All listed companies: {@code /files/company_tickers.json}. */
    public Mono<Map<String, CompanyTickerEntry>> fetchCompanyTickers() {
        return get(uri(props.wwwBaseUrl(), "files", "company_tickers.json"), TICKERS_TYPE);
    }

    /** Company name and recent filings: {@code data.sec.gov/submissions/CIK##########.json}. */
    public Mono<SubmissionsResponse> fetchSubmissions(Cik cik) {
        return get(uri(props.dataBaseUrl(), "submissions", "CIK" + cik.padded() + ".json"),
                ParameterizedTypeReference.forType(SubmissionsResponse.class));
    }

    /** All XBRL facts for a company: {@code data.sec.gov/api/xbrl/companyfacts/CIK##########.json}. */
    public Mono<JsonNode> fetchCompanyFacts(Cik cik) {
        return get(uri(props.dataBaseUrl(), "api", "xbrl", "companyfacts", "CIK" + cik.padded() + ".json"),
                ParameterizedTypeReference.forType(JsonNode.class));
    }

    /** A filing document: {@code /Archives/edgar/data/{cik}/{accessionWithoutDashes}/{fileName}}. */
    public Mono<String> fetchFilingDocument(Cik cik, AccessionNumber accession, String fileName) {
        if (fileName == null || !SAFE_FILE_NAME.matcher(fileName).matches() || fileName.contains("..")) {
            return Mono.error(new IllegalArgumentException("Invalid filing document name: " + fileName));
        }
        return get(uri(props.wwwBaseUrl(), "Archives", "edgar", "data", cik.toString(),
                accession.withoutDashes(), fileName), ParameterizedTypeReference.forType(String.class));
    }

    private static URI uri(URI base, String... pathSegments) {
        return UriComponentsBuilder.fromUri(base).pathSegment(pathSegments).build().encode().toUri();
    }

    private <T> Mono<T> get(URI uri, ParameterizedTypeReference<T> type) {
        EdgarProperties.Retry retry = props.retry();
        // retryWhen re-subscribes the whole chain, so every attempt waits for its own rate-limit token.
        return rateLimiter.acquire()
                .then(Mono.defer(() -> webClient.get().uri(uri).retrieve().bodyToMono(type)))
                .retryWhen(Retry.backoff(retry.maxAttempts() - 1L, retry.initialBackoff())
                        .maxBackoff(retry.maxBackoff())
                        .filter(EdgarClient::isRetryable)
                        .doBeforeRetry(s -> log.warn("Retrying SEC request {} (attempt {}): {}",
                                uri.getPath(), s.totalRetries() + 2, describe(s.failure())))
                        .onRetryExhaustedThrow((spec, s) -> new EdgarException.Unavailable(
                                "SEC EDGAR is not responding right now (" + describe(s.failure())
                                        + "). Please try again in a minute.", s.failure())))
                .onErrorMap(e -> !(e instanceof EdgarException) && !(e instanceof IllegalArgumentException),
                        e -> translate(uri, e))
                .doOnSuccess(r -> log.debug("SEC request ok: {}", uri.getPath()));
    }

    static boolean isRetryable(Throwable e) {
        if (e instanceof WebClientResponseException r) {
            return r.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value() || r.getStatusCode().is5xxServerError();
        }
        return e instanceof WebClientRequestException;
    }

    private static EdgarException translate(URI uri, Throwable e) {
        if (e instanceof WebClientResponseException r) {
            int status = r.getStatusCode().value();
            if (status == 404) {
                return new EdgarException.NotFound("SEC EDGAR has no document at " + uri.getPath());
            }
            if (status == 403) {
                // The SEC answers 403 both for a missing/invalid User-Agent and for archive paths that do not exist.
                return new EdgarException("SEC EDGAR refused the request for " + uri.getPath() + " (403). Either the "
                        + "document does not exist, or edgar.user-agent is not set to \"Your Name your@email.com\".", e);
            }
            return new EdgarException("SEC EDGAR returned HTTP " + status + " for " + uri.getPath(), e);
        }
        if (e instanceof WebClientRequestException) {
            return new EdgarException.Unavailable("Could not reach SEC EDGAR: " + describe(e), e);
        }
        return new EdgarException("Unexpected error reading SEC EDGAR response for " + uri.getPath(), e);
    }

    private static String describe(Throwable e) {
        if (e instanceof WebClientResponseException r) {
            return "HTTP " + r.getStatusCode().value();
        }
        return e.getClass().getSimpleName();
    }
}
