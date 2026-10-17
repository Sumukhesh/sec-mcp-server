package io.github.sumukhesh.secmcp.edgar;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.sumukhesh.secmcp.config.EdgarProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

class EdgarClientTest {

    private static final String USER_AGENT = "Test Runner test@example.com";
    private static final String TICKERS = "/files/company_tickers.json";
    private static final String APPLE_SUBMISSIONS = "/submissions/CIK0000320193.json";

    @RegisterExtension
    static WireMockExtension sec = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private EdgarClient client(int rateLimitPerSecond, int maxAttempts) {
        URI base = URI.create(sec.baseUrl());
        EdgarProperties props = new EdgarProperties(USER_AGENT, base, base, rateLimitPerSecond,
                Duration.ofSeconds(2), 10_000_000L,
                new EdgarProperties.Retry(maxAttempts, Duration.ofMillis(10), Duration.ofMillis(50)),
                Duration.ofHours(24));
        return new EdgarClient(props);
    }

    private EdgarClient client() {
        return client(9, 3);
    }

    @Test
    void sendsUserAgentAndParsesTickerFile() {
        sec.stubFor(get(TICKERS).willReturn(okJson("""
                {"0":{"cik_str":320193,"ticker":"AAPL","title":"Apple Inc."},
                 "1":{"cik_str":789019,"ticker":"MSFT","title":"MICROSOFT CORP"}}""")));

        StepVerifier.create(client().fetchCompanyTickers())
                .assertNext(m -> assertThat(m.values()).containsExactly(
                        new CompanyTickerEntry(320193, "AAPL", "Apple Inc."),
                        new CompanyTickerEntry(789019, "MSFT", "MICROSOFT CORP")))
                .verifyComplete();

        sec.verify(getRequestedFor(urlPathEqualTo(TICKERS)).withHeader("User-Agent", equalTo(USER_AGENT)));
    }

    @Test
    void requestsSubmissionsWithZeroPaddedCik() {
        sec.stubFor(get(APPLE_SUBMISSIONS).willReturn(okJson("""
                {"cik":"320193","name":"Apple Inc.","tickers":["AAPL"],"sic":"3571",
                 "filings":{"recent":{"accessionNumber":["0000320193-24-000123"],"filingDate":["2024-11-01"],
                 "reportDate":["2024-09-28"],"form":["10-K"],"primaryDocument":["aapl-20240928.htm"],
                 "primaryDocDescription":["10-K"]}}}""")));

        StepVerifier.create(client().fetchSubmissions(new Cik(320193)))
                .assertNext(s -> {
                    assertThat(s.name()).isEqualTo("Apple Inc.");
                    assertThat(s.filings().recent().form()).containsExactly("10-K");
                    assertThat(s.filings().recent().primaryDocument()).containsExactly("aapl-20240928.htm");
                })
                .verifyComplete();
    }

    @Test
    void buildsArchivesUrlFromCikAccessionAndFileName() {
        String path = "/Archives/edgar/data/320193/000032019324000123/aapl-20240928.htm";
        sec.stubFor(get(path).willReturn(aResponse().withBody("<html>10-K</html>")));

        StepVerifier.create(client().fetchFilingDocument(new Cik(320193),
                        AccessionNumber.parse("0000320193-24-000123"), "aapl-20240928.htm"))
                .expectNext("<html>10-K</html>")
                .verifyComplete();
    }

    @Test
    void rejectsUnsafeDocumentNamesWithoutCallingSec() {
        StepVerifier.create(client().fetchFilingDocument(new Cik(320193),
                        AccessionNumber.parse("0000320193-24-000123"), "../../../etc/passwd"))
                .expectError(IllegalArgumentException.class)
                .verify();

        assertThat(sec.getAllServeEvents()).isEmpty();
    }

    @Test
    void retriesOn429ThenSucceeds() {
        sec.stubFor(get(TICKERS).inScenario("throttle").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429)).willSetStateTo("second"));
        sec.stubFor(get(TICKERS).inScenario("throttle").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(503)).willSetStateTo("ok"));
        sec.stubFor(get(TICKERS).inScenario("throttle").whenScenarioStateIs("ok")
                .willReturn(okJson("{}")));

        StepVerifier.create(client().fetchCompanyTickers())
                .assertNext(m -> assertThat(m).isEmpty())
                .verifyComplete();

        sec.verify(3, getRequestedFor(urlPathEqualTo(TICKERS)).withHeader("User-Agent", equalTo(USER_AGENT)));
    }

    @Test
    void givesUpAfterMaxAttemptsWithFriendlyError() {
        sec.stubFor(get(TICKERS).willReturn(aResponse().withStatus(503)));

        StepVerifier.create(client(9, 3).fetchCompanyTickers())
                .expectErrorSatisfies(e -> assertThat(e)
                        .isInstanceOf(EdgarException.Unavailable.class)
                        .hasMessageContaining("not responding")
                        .hasMessageContaining("HTTP 503"))
                .verify();

        sec.verify(3, getRequestedFor(urlPathEqualTo(TICKERS)));
    }

    @Test
    void doesNotRetryNotFound() {
        sec.stubFor(get(APPLE_SUBMISSIONS).willReturn(aResponse().withStatus(404)));

        StepVerifier.create(client().fetchSubmissions(new Cik(320193)))
                .expectError(EdgarException.NotFound.class)
                .verify();

        sec.verify(1, getRequestedFor(urlPathEqualTo(APPLE_SUBMISSIONS)));
    }

    @Test
    void forbiddenPointsAtUserAgentAndIsNotRetried() {
        sec.stubFor(get(TICKERS).willReturn(aResponse().withStatus(403)));

        StepVerifier.create(client().fetchCompanyTickers())
                .expectErrorSatisfies(e -> assertThat(e).isExactlyInstanceOf(EdgarException.class)
                        .hasMessageContaining("edgar.user-agent"))
                .verify();

        sec.verify(1, getRequestedFor(urlPathEqualTo(TICKERS)));
    }

    @Test
    void concurrentRequestsAreSpacedByRateLimiter() {
        sec.stubFor(get(TICKERS).willReturn(okJson("{}")));
        EdgarClient client = client(5, 1); // 5 req/s -> 200 ms apart

        long start = System.nanoTime();
        StepVerifier.create(Flux.range(0, 6).flatMap(i -> client.fetchCompanyTickers()))
                .expectNextCount(6)
                .verifyComplete();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(5 * 200 - 20));
        List<Long> arrivals = sec.getAllServeEvents().stream()
                .map(e -> e.getRequest().getLoggedDate().getTime()).sorted().toList();
        for (int i = 1; i < arrivals.size(); i++) {
            assertThat(arrivals.get(i) - arrivals.get(i - 1)).isGreaterThanOrEqualTo(150L);
        }
    }

    @Test
    void retriesAlsoWaitForTheRateLimiter() {
        sec.stubFor(get(TICKERS).willReturn(aResponse().withStatus(500)));
        EdgarClient client = client(2, 3); // 2 req/s -> retries must be >= 500 ms apart

        long start = System.nanoTime();
        StepVerifier.create(client.fetchCompanyTickers())
                .expectError(EdgarException.Unavailable.class)
                .verify();

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(980));
        sec.verify(3, getRequestedFor(urlPathEqualTo(TICKERS)));
    }
}
