package io.github.sumukhesh.secmcp.edgar;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.sumukhesh.secmcp.config.EdgarProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TickerCikResolverTest {

    private static final Map<String, CompanyTickerEntry> TICKERS = Map.of(
            "0", new CompanyTickerEntry(320193, "AAPL", "Apple Inc."),
            "1", new CompanyTickerEntry(1652044, "GOOGL", "Alphabet Inc."),
            "2", new CompanyTickerEntry(1652044, "GOOG", "Alphabet Inc."),
            "3", new CompanyTickerEntry(1067983, "BRK-B", "BERKSHIRE HATHAWAY INC"));

    private final EdgarClient client = mock(EdgarClient.class);
    private final AtomicInteger fetches = new AtomicInteger();
    private VirtualTimeScheduler time;
    private TickerCikResolver resolver;

    @BeforeEach
    void setUp() {
        time = VirtualTimeScheduler.getOrSet(); // Mono.cache TTLs run on virtual time
        respondWith(Mono.just(TICKERS));
        EdgarProperties props = new EdgarProperties("t t@example.com", URI.create("http://x"), URI.create("http://x"),
                8, Duration.ofSeconds(1), 1L, new EdgarProperties.Retry(1, Duration.ZERO, Duration.ZERO),
                Duration.ofHours(24));
        resolver = new TickerCikResolver(client, props);
    }

    @AfterEach
    void tearDown() {
        VirtualTimeScheduler.reset();
    }

    private void respondWith(Mono<Map<String, CompanyTickerEntry>> response) {
        when(client.fetchCompanyTickers()).thenReturn(Mono.defer(() -> {
            fetches.incrementAndGet();
            return response;
        }));
    }

    private Optional<CompanyRef> ticker(String t) {
        return resolver.findByTicker(t).block();
    }

    @ParameterizedTest
    @ValueSource(strings = {"AAPL", "aapl", " $aapl "})
    void findsTickerCaseInsensitively(String query) {
        assertThat(ticker(query)).contains(new CompanyRef(new Cik(320193), "AAPL", "Apple Inc."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BRK.B", "brk-b", "BRK/B"})
    void normalizesClassShareSeparators(String query) {
        assertThat(ticker(query)).map(CompanyRef::cik).contains(new Cik(1067983));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "NOPE", "A;DROP TABLE", "WAYTOOLONGTICKERSYMBOL"})
    void unknownOrInvalidTickersAreEmpty(String query) {
        assertThat(ticker(query)).isEmpty();
    }

    @Test
    void findByCikReturnsPrimaryTicker() {
        assertThat(resolver.findByCik(new Cik(1652044)).block()).map(CompanyRef::ticker).contains("GOOGL");
    }

    @Test
    void allCompaniesHasOneEntryPerCik() {
        assertThat(resolver.allCompanies().block()).extracting(CompanyRef::ticker)
                .containsExactly("AAPL", "GOOGL", "BRK-B");
    }

    @Test
    void concurrentFirstCallsShareOneDownload() {
        Flux.range(0, 20).flatMap(i -> resolver.findByTicker("AAPL")).blockLast();

        assertThat(fetches).hasValue(1);
    }

    @Test
    void reusesIndexUntilRefreshInterval() {
        ticker("AAPL");
        time.advanceTimeBy(Duration.ofHours(23));
        ticker("AAPL");
        assertThat(fetches).hasValue(1);

        time.advanceTimeBy(Duration.ofHours(2));
        ticker("AAPL");
        assertThat(fetches).hasValue(2);
    }

    @Test
    void servesStaleIndexWhenRefreshFails() {
        ticker("AAPL");
        respondWith(Mono.error(new EdgarException.Unavailable("down", null)));
        time.advanceTimeBy(Duration.ofHours(25));

        assertThat(ticker("AAPL")).isPresent();
        assertThat(fetches).hasValue(2);

        // Stale data is only reused for a short time before trying the SEC again.
        respondWith(Mono.just(TICKERS));
        time.advanceTimeBy(TickerCikResolver.STALE_RETRY_INTERVAL.plusSeconds(1));
        ticker("AAPL");
        assertThat(fetches).hasValue(3);
    }

    @Test
    void firstLoadFailureIsReportedAndNotCached() {
        respondWith(Mono.error(new EdgarException.Unavailable("down", null)));
        assertThatThrownBy(() -> ticker("AAPL")).isInstanceOf(EdgarException.Unavailable.class);

        respondWith(Mono.just(TICKERS));
        assertThat(ticker("AAPL")).isPresent();
    }
}
