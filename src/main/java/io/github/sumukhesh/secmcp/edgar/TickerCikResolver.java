package io.github.sumukhesh.secmcp.edgar;

import java.time.Duration;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import io.github.sumukhesh.secmcp.config.EdgarProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Resolves tickers and CIKs using the SEC's {@code company_tickers.json} (about 10k companies),
 * held in memory and re-fetched after {@code edgar.ticker-refresh-interval}.
 *
 * <p>The file is loaded lazily on first use, and concurrent first callers share one download.
 * If a refresh fails, the previous index keeps serving (marked stale) and a new fetch is
 * attempted after {@link #STALE_RETRY_INTERVAL}, so an SEC outage never takes lookups down.
 */
@Component
public class TickerCikResolver {

    private static final Logger log = LoggerFactory.getLogger(TickerCikResolver.class);

    static final Duration STALE_RETRY_INTERVAL = Duration.ofMinutes(5);
    private static final Pattern TICKER = Pattern.compile("[A-Z0-9][A-Z0-9-]{0,9}");

    private final EdgarClient edgarClient;
    private final AtomicReference<Index> lastGood = new AtomicReference<>();
    private final Mono<Index> index;

    public TickerCikResolver(EdgarClient edgarClient, EdgarProperties props) {
        this.edgarClient = edgarClient;
        this.index = Mono.defer(this::load)
                .cache(i -> i.stale() ? STALE_RETRY_INTERVAL : props.tickerRefreshInterval(),
                        error -> Duration.ZERO,  // never cache failures
                        () -> Duration.ZERO);
    }

    /** Looks up a ticker, case-insensitively. Accepts "brk.b", "BRK-B" or "$aapl". */
    public Mono<Optional<CompanyRef>> findByTicker(String ticker) {
        Optional<String> normalized = normalizeTicker(ticker);
        if (normalized.isEmpty()) {
            return Mono.just(Optional.empty());
        }
        return index.map(i -> Optional.ofNullable(i.byTicker().get(normalized.get())));
    }

    /** Looks up a company by CIK, returning its primary (first-listed) ticker. */
    public Mono<Optional<CompanyRef>> findByCik(Cik cik) {
        return index.map(i -> Optional.ofNullable(i.byCik().get(cik)));
    }

    /** One entry per company (primary ticker), in SEC file order. Used for name search. */
    public Mono<List<CompanyRef>> allCompanies() {
        return index.map(i -> List.copyOf(i.byCik().values()));
    }

    static Optional<String> normalizeTicker(String raw) {
        if (raw == null || raw.length() > 20) {
            return Optional.empty();
        }
        String t = raw.strip().toUpperCase(Locale.ROOT).replace('.', '-').replace('/', '-');
        if (t.startsWith("$")) {
            t = t.substring(1);
        }
        return TICKER.matcher(t).matches() ? Optional.of(t) : Optional.empty();
    }

    private Mono<Index> load() {
        return edgarClient.fetchCompanyTickers()
                .map(TickerCikResolver::buildIndex)
                .doOnNext(i -> {
                    lastGood.set(i);
                    log.info("Loaded SEC ticker index: {} tickers, {} companies", i.byTicker().size(), i.byCik().size());
                })
                .onErrorResume(e -> {
                    Index previous = lastGood.get();
                    if (previous == null) {
                        return Mono.error(e);
                    }
                    log.warn("Ticker index refresh failed, serving stale data: {}", e.getMessage());
                    return Mono.just(previous.asStale());
                });
    }

    static Index buildIndex(Map<String, CompanyTickerEntry> raw) {
        Map<String, CompanyRef> byTicker = new LinkedHashMap<>();
        Map<Cik, CompanyRef> byCik = new LinkedHashMap<>();
        raw.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> orderOf(e.getKey())))
                .map(Map.Entry::getValue)
                .filter(e -> e.cik() > 0 && e.ticker() != null && e.title() != null)
                .forEach(e -> {
                    CompanyRef ref = new CompanyRef(new Cik(e.cik()), e.ticker().toUpperCase(Locale.ROOT), e.title());
                    byTicker.putIfAbsent(ref.ticker(), ref);
                    byCik.putIfAbsent(ref.cik(), ref);
                });
        return new Index(Collections.unmodifiableMap(byTicker), Collections.unmodifiableMap(byCik), false);
    }

    private static int orderOf(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    record Index(Map<String, CompanyRef> byTicker, Map<Cik, CompanyRef> byCik, boolean stale) {
        Index asStale() {
            return new Index(byTicker, byCik, true);
        }
    }
}
