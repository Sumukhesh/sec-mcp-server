package io.github.sumukhesh.secmcp.parsing;

import java.time.Year;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import io.github.sumukhesh.secmcp.edgar.AccessionNumber;
import io.github.sumukhesh.secmcp.edgar.Cik;
import io.github.sumukhesh.secmcp.edgar.CompanyRef;
import io.github.sumukhesh.secmcp.edgar.EdgarClient;
import io.github.sumukhesh.secmcp.edgar.EdgarException;
import io.github.sumukhesh.secmcp.edgar.SubmissionsResponse;
import io.github.sumukhesh.secmcp.edgar.TickerCikResolver;
import io.github.sumukhesh.secmcp.model.FilingMetadata;
import io.github.sumukhesh.secmcp.model.FilingSectionResult;
import io.github.sumukhesh.secmcp.parsing.SectionExtractor.FilingForm;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Returns one page of a 10-K/10-Q section, so a 200-page filing fits a model's context window a
 * slice at a time.
 *
 * <p>A filing is identified by accession number, or by ticker + form + optional fiscal year. The
 * accession number's prefix is the CIK of whoever submitted the filing; for filings submitted by a
 * filing agent that is not the company, so a ticker is needed as well.
 *
 * <p>Extracted sections are kept in a small in-memory LRU so paging through a section downloads
 * the document once. (Phase 2 replaces this with the Postgres cache.)
 */
@Service
public class FilingSectionService {

    public static final int DEFAULT_LIMIT = 10_000;
    public static final int MAX_LIMIT = 20_000;
    static final int CACHED_FILINGS = 16;

    private final EdgarClient edgarClient;
    private final TickerCikResolver resolver;
    private final Map<String, Mono<ParsedFiling>> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Mono<ParsedFiling>> eldest) {
            return size() > CACHED_FILINGS;
        }
    };

    public FilingSectionService(EdgarClient edgarClient, TickerCikResolver resolver) {
        this.edgarClient = edgarClient;
        this.resolver = resolver;
    }

    public record Request(String accessionNumber, String ticker, String form, Integer fiscalYear,
                          String section, Integer offset, Integer limit) {
    }

    record ResolvedFiling(FilingMetadata metadata, Cik cik, String primaryDocument) {
    }

    record ParsedFiling(ResolvedFiling filing, Map<FilingSection, String> sections) {
    }

    public Mono<FilingSectionResult> getSection(Request request) {
        FilingSection section;
        int offset;
        int limit;
        try {
            section = parseSection(request.section());
            offset = request.offset() == null ? 0 : request.offset();
            limit = request.limit() == null ? DEFAULT_LIMIT : request.limit();
            if (offset < 0) {
                throw new IllegalArgumentException("offset must be 0 or greater.");
            }
            if (limit < 1 || limit > MAX_LIMIT) {
                throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT + " characters.");
            }
            validateIdentifiers(request);
        } catch (IllegalArgumentException e) {
            return Mono.error(e);
        }

        return parsedFiling(request).map(parsed -> {
            String text = parsed.sections().get(section);
            if (text == null) {
                throw new IllegalArgumentException("Could not find the " + section.title() + " section in this "
                        + parsed.filing().metadata().form() + ". Smaller reporting companies may omit it; try the "
                        + "other section or another filing.");
            }
            return page(section, parsed.filing().metadata(), text, offset, limit);
        });
    }

    static FilingSectionResult page(FilingSection section, FilingMetadata filing, String text, int offset, int limit) {
        int total = text.length();
        if (offset > total) {
            throw new IllegalArgumentException("offset " + offset + " is past the end of the section (total_chars "
                    + total + ").");
        }
        int end = Math.min(total, offset + limit);
        if (end < total && end > offset && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--; // never split a surrogate pair
        }
        String slice = text.substring(offset, end);
        return new FilingSectionResult(section.name(), filing, slice, offset, slice.length(), total, end < total);
    }

    private Mono<ParsedFiling> parsedFiling(Request request) {
        // With an accession number the parsed filing can be reused without re-resolving it.
        if (request.accessionNumber() != null && !request.accessionNumber().isBlank()) {
            String key = AccessionNumber.parse(request.accessionNumber()).value();
            return cached(key, () -> resolve(request).flatMap(this::download));
        }
        return resolve(request).flatMap(f -> cached(f.metadata().accessionNumber(), () -> download(f)));
    }

    private Mono<ParsedFiling> cached(String accession, Supplier<Mono<ParsedFiling>> loader) {
        synchronized (cache) {
            Mono<ParsedFiling> existing = cache.get(accession);
            if (existing != null) {
                return existing;
            }
            Mono<ParsedFiling> loading = loader.get()
                    .doOnError(e -> {
                        synchronized (cache) {
                            cache.remove(accession);
                        }
                    })
                    .cache();
            cache.put(accession, loading);
            return loading;
        }
    }

    private Mono<ParsedFiling> download(ResolvedFiling filing) {
        FilingForm form = FilingForm.fromSecForm(filing.metadata().form()).orElseThrow();
        return edgarClient.fetchFilingDocument(filing.cik(), new AccessionNumber(filing.metadata().accessionNumber()),
                        filing.primaryDocument())
                // HTML parsing is CPU-bound (~100 ms for a large 10-K); keep it off the Netty event loop.
                .publishOn(Schedulers.boundedElastic())
                .map(html -> {
                    String text = HtmlToText.convert(html);
                    Map<FilingSection, String> sections = new EnumMap<>(FilingSection.class);
                    for (FilingSection s : FilingSection.values()) {
                        SectionExtractor.extract(text, form, s).ifPresent(t -> sections.put(s, t));
                    }
                    return new ParsedFiling(filing, sections);
                });
    }

    private Mono<ResolvedFiling> resolve(Request request) {
        Mono<Optional<CompanyRef>> company = request.ticker() == null || request.ticker().isBlank()
                ? Mono.just(Optional.empty())
                : resolver.findByTicker(request.ticker()).map(found -> {
                    if (found.isEmpty()) {
                        throw new IllegalArgumentException("Unknown ticker '" + request.ticker().strip()
                                + "'. Use search_company to find the company's ticker.");
                    }
                    return found;
                });

        return company.flatMap(ref -> {
            Cik cik = ref.map(CompanyRef::cik)
                    .orElseGet(() -> Cik.parse(AccessionNumber.parse(request.accessionNumber()).value().substring(0, 10)));
            return edgarClient.fetchSubmissions(cik)
                    .onErrorMap(e -> ref.isEmpty() && e instanceof EdgarException.NotFound,
                            e -> agentFiledHint(request.accessionNumber()))
                    .map(submissions -> pickFiling(request, cik, ref.map(CompanyRef::ticker), submissions));
        });
    }

    static ResolvedFiling pickFiling(Request request, Cik cik, Optional<String> ticker, SubmissionsResponse s) {
        SubmissionsResponse.Recent r = s.filings() == null ? null : s.filings().recent();
        if (r == null || r.accessionNumber() == null || r.form() == null || r.primaryDocument() == null) {
            throw new IllegalArgumentException("SEC EDGAR returned no filing list for CIK " + cik + ".");
        }
        int n = Math.min(r.accessionNumber().size(), Math.min(r.form().size(), r.primaryDocument().size()));
        String tickerOrFirst = ticker.orElse(s.tickers() == null || s.tickers().isEmpty() ? null : s.tickers().getFirst());

        int index;
        if (request.accessionNumber() != null && !request.accessionNumber().isBlank()) {
            String accession = AccessionNumber.parse(request.accessionNumber()).value();
            index = IntStream.range(0, n).filter(i -> accession.equals(r.accessionNumber().get(i))).findFirst()
                    .orElseThrow(() -> ticker.isEmpty()
                            ? agentFiledHint(accession)
                            : new IllegalArgumentException("Filing " + accession + " is not among " + s.name()
                                    + "'s recent filings. Use search_company to list its filings."));
        } else {
            String form = parseForm(request.form());
            List<Integer> candidates = IntStream.range(0, n).filter(i -> form.equals(r.form().get(i))).boxed().toList();
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException(s.name() + " has no " + form + " among its recent filings.");
            }
            if (request.fiscalYear() == null) {
                index = candidates.getFirst();
            } else {
                String year = Integer.toString(request.fiscalYear());
                index = candidates.stream().filter(i -> reportDate(r, i).startsWith(year)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("No " + form + " for fiscal year " + year
                                + " found for " + s.name() + " (available fiscal years: "
                                + String.join(", ", candidates.stream().map(i -> reportDate(r, i))
                                .filter(d -> d.length() >= 4).map(d -> d.substring(0, 4)).distinct().limit(10).toList())
                                + ")."));
            }
        }

        String form = r.form().get(index);
        if (FilingForm.fromSecForm(form).isEmpty()) {
            throw new IllegalArgumentException("Filing " + r.accessionNumber().get(index) + " is a " + form
                    + ". Sections are only available for 10-K and 10-Q filings.");
        }
        FilingMetadata metadata = new FilingMetadata(cik.padded(), s.name(), tickerOrFirst, form,
                r.filingDate() != null && index < r.filingDate().size() ? r.filingDate().get(index) : null,
                blankToNull(reportDate(r, index)), r.accessionNumber().get(index));
        return new ResolvedFiling(metadata, cik, r.primaryDocument().get(index));
    }

    private static IllegalArgumentException agentFiledHint(String accession) {
        return new IllegalArgumentException("Could not locate filing " + accession + " from its accession number "
                + "alone; it was probably submitted by a filing agent. Call again with the company's ticker as well.");
    }

    private static void validateIdentifiers(Request request) {
        boolean hasAccession = request.accessionNumber() != null && !request.accessionNumber().isBlank();
        boolean hasTicker = request.ticker() != null && !request.ticker().isBlank();
        if (!hasAccession && !hasTicker) {
            throw new IllegalArgumentException("Provide accession_number (from search_company), or ticker with "
                    + "optional form and fiscal_year.");
        }
        if (hasAccession) {
            AccessionNumber.parse(request.accessionNumber());
        }
        if (request.fiscalYear() != null) {
            int max = Year.now().getValue() + 1;
            if (request.fiscalYear() < 1993 || request.fiscalYear() > max) {
                throw new IllegalArgumentException("fiscal_year must be between 1993 and " + max + ".");
            }
        }
        if (!hasAccession) {
            parseForm(request.form());
        }
    }

    static FilingSection parseSection(String raw) {
        String s = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT).replaceAll("[\\s&-]+", "_");
        return switch (s) {
            case "RISK_FACTORS", "RISKS", "ITEM_1A" -> FilingSection.RISK_FACTORS;
            case "MDA", "MD_A", "MD_AND_A", "ITEM_7" -> FilingSection.MDA;
            default -> throw new IllegalArgumentException("section must be RISK_FACTORS or MDA (Management's "
                    + "Discussion and Analysis).");
        };
    }

    static String parseForm(String raw) {
        if (raw == null || raw.isBlank()) {
            return "10-K";
        }
        String f = raw.strip().toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
        return switch (f) {
            case "10K" -> "10-K";
            case "10Q" -> "10-Q";
            case "8K" -> throw new IllegalArgumentException("8-K filings have no Risk Factors or MD&A sections. "
                    + "Use form 10-K or 10-Q.");
            default -> throw new IllegalArgumentException("form must be 10-K or 10-Q.");
        };
    }

    private static String reportDate(SubmissionsResponse.Recent r, int i) {
        return r.reportDate() != null && i < r.reportDate().size() && r.reportDate().get(i) != null
                ? r.reportDate().get(i) : "";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
