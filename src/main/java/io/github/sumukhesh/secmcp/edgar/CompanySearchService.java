package io.github.sumukhesh.secmcp.edgar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import io.github.sumukhesh.secmcp.model.CompanyInfo;
import io.github.sumukhesh.secmcp.model.FilingInfo;
import io.github.sumukhesh.secmcp.model.SearchCompanyResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Finds a company by ticker, CIK or name and lists its recent core filings.
 * Costs one SEC request (submissions) plus the ticker file when it is not already cached.
 */
@Service
public class CompanySearchService {

    static final int MAX_QUERY_LENGTH = 100;
    static final int MAX_FILINGS = 10;
    static final int MAX_OTHER_MATCHES = 4;
    static final Set<String> CORE_FORMS = Set.of("10-K", "10-Q", "8-K");

    private final TickerCikResolver resolver;
    private final EdgarClient edgarClient;

    public CompanySearchService(TickerCikResolver resolver, EdgarClient edgarClient) {
        this.resolver = resolver;
        this.edgarClient = edgarClient;
    }

    public Mono<SearchCompanyResult> search(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.strip();
        if (query.isEmpty()) {
            return Mono.error(new IllegalArgumentException(
                    "Provide a company name or ticker, for example 'Apple' or 'AAPL'."));
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            return Mono.error(new IllegalArgumentException(
                    "Query is too long (max " + MAX_QUERY_LENGTH + " characters). Use a ticker or company name."));
        }

        return Mono.zip(resolver.findByTicker(query), resolver.allCompanies())
                .flatMap(t -> {
                    List<CompanyMatcher.Match> matches =
                            CompanyMatcher.rank(query, t.getT2(), t.getT1(), 1 + MAX_OTHER_MATCHES);
                    if (matches.isEmpty()) {
                        return Mono.error(new CompanyNotFoundException(query));
                    }
                    CompanyRef best = matches.getFirst().company();
                    List<CompanyInfo> others = matches.stream().skip(1).map(m -> toInfo(m.company())).toList();
                    return edgarClient.fetchSubmissions(best.cik())
                            .map(s -> new SearchCompanyResult(
                                    new CompanyInfo(best.cik().padded(), officialName(s, best), best.ticker()),
                                    recentCoreFilings(s, MAX_FILINGS),
                                    others));
                });
    }

    /** Newest-first 10-K/10-Q/8-K filings from EDGAR's columnar "recent" arrays. */
    static List<FilingInfo> recentCoreFilings(SubmissionsResponse submissions, int limit) {
        SubmissionsResponse.Recent r = submissions.filings() == null ? null : submissions.filings().recent();
        if (r == null || r.form() == null || r.accessionNumber() == null || r.filingDate() == null) {
            return List.of();
        }
        int n = Math.min(r.form().size(), Math.min(r.accessionNumber().size(), r.filingDate().size()));
        List<FilingInfo> filings = new ArrayList<>(IntStream.range(0, n)
                .filter(i -> CORE_FORMS.contains(r.form().get(i)))
                .mapToObj(i -> new FilingInfo(r.form().get(i), r.filingDate().get(i),
                        blankToNull(r.reportDate() != null && i < r.reportDate().size() ? r.reportDate().get(i) : null),
                        r.accessionNumber().get(i)))
                .toList());
        filings.sort(Comparator.comparing(FilingInfo::filingDate).reversed()); // stable: keeps EDGAR order on ties
        return List.copyOf(filings.subList(0, Math.min(limit, filings.size())));
    }

    private static String officialName(SubmissionsResponse s, CompanyRef fallback) {
        return s.name() == null || s.name().isBlank() ? fallback.name() : s.name();
    }

    private static CompanyInfo toInfo(CompanyRef c) {
        return new CompanyInfo(c.cik().padded(), c.name(), c.ticker());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** No company matched; the message tells the model how to retry. */
    public static class CompanyNotFoundException extends IllegalArgumentException {
        CompanyNotFoundException(String query) {
            super("No company found for '" + query.replaceAll("\\p{Cntrl}", "") + "'. Try the ticker symbol "
                    + "(e.g. AAPL) or the company's registered name (e.g. 'Apple'). Only SEC-registered, "
                    + "exchange-listed companies are searchable.");
        }
    }
}
