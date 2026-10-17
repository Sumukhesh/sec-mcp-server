package io.github.sumukhesh.secmcp.parsing;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

import io.github.sumukhesh.secmcp.edgar.AccessionNumber;
import io.github.sumukhesh.secmcp.edgar.Cik;
import io.github.sumukhesh.secmcp.edgar.CompanyRef;
import io.github.sumukhesh.secmcp.edgar.EdgarClient;
import io.github.sumukhesh.secmcp.edgar.SubmissionsResponse;
import io.github.sumukhesh.secmcp.edgar.TickerCikResolver;
import io.github.sumukhesh.secmcp.model.FilingMetadata;
import io.github.sumukhesh.secmcp.model.FilingSectionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FilingSectionServiceTest {

    private static final Cik APPLE = new Cik(320193);
    private static final String APPLE_10K_2025 = "0000320193-25-000079";
    private static final String APPLE_10Q_2026 = "0000320193-26-000020";

    private final EdgarClient client = mock(EdgarClient.class);
    private final TickerCikResolver resolver = mock(TickerCikResolver.class);
    private final FilingSectionService service = new FilingSectionService(client, resolver);

    @BeforeEach
    void setUp() throws Exception {
        SubmissionsResponse submissions;
        try (InputStream in = getClass().getResourceAsStream("/fixtures/edgar/submissions-CIK0000320193.json")) {
            submissions = JsonMapper.builder().build().readValue(in, SubmissionsResponse.class);
        }
        when(client.fetchSubmissions(APPLE)).thenReturn(Mono.just(submissions));
        when(client.fetchFilingDocument(eq(APPLE), any(), eq("aapl-20250927.htm")))
                .thenReturn(Mono.fromCallable(() -> gunzip("aapl-20250927-10k.htm.gz")));
        when(client.fetchFilingDocument(eq(APPLE), any(), eq("aapl-20260627.htm")))
                .thenReturn(Mono.fromCallable(() -> gunzip("aapl-20260627-10q.htm.gz")));
        when(resolver.findByTicker(anyString())).thenReturn(Mono.just(Optional.empty()));
        when(resolver.findByTicker("AAPL")).thenReturn(Mono.just(Optional.of(new CompanyRef(APPLE, "AAPL", "Apple Inc."))));
    }

    private static String gunzip(String fixture) throws Exception {
        try (InputStream in = new GZIPInputStream(
                FilingSectionServiceTest.class.getResourceAsStream("/fixtures/filings/" + fixture))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static FilingSectionService.Request byAccession(String accession, String section, Integer offset, Integer limit) {
        return new FilingSectionService.Request(accession, null, null, null, section, offset, limit);
    }

    private static FilingSectionService.Request byTicker(String ticker, String form, Integer year, String section) {
        return new FilingSectionService.Request(null, ticker, form, year, section, null, null);
    }

    @Test
    void returnsFirstPageOfRiskFactorsByAccessionNumber() {
        StepVerifier.create(service.getSection(byAccession(APPLE_10K_2025, "RISK_FACTORS", null, null)))
                .assertNext(r -> {
                    assertThat(r.section()).isEqualTo("RISK_FACTORS");
                    assertThat(r.filing()).isEqualTo(new FilingMetadata("0000320193", "Apple Inc.", "AAPL", "10-K",
                            "2025-10-31", "2025-09-27", APPLE_10K_2025));
                    assertThat(r.text()).startsWith("Item 1A. Risk Factors");
                    assertThat(r.offset()).isZero();
                    assertThat(r.returnedChars()).isEqualTo(FilingSectionService.DEFAULT_LIMIT).isEqualTo(r.text().length());
                    assertThat(r.totalChars()).isGreaterThan(50_000);
                    assertThat(r.hasMore()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void pagingReassemblesTheWholeSectionAndDownloadsOnce() {
        StringBuilder all = new StringBuilder();
        int offset = 0;
        FilingSectionResult page;
        int pages = 0;
        do {
            page = service.getSection(byAccession(APPLE_10K_2025, "risk factors", offset, 20_000)).block();
            all.append(page.text());
            offset += page.returnedChars();
            pages++;
        } while (page.hasMore());

        assertThat(all).hasSize(page.totalChars());
        assertThat(all.toString()).startsWith("Item 1A. Risk Factors").endsWith("employee retention.");
        assertThat(pages).isEqualTo((page.totalChars() + 19_999) / 20_000);
        verify(client, times(1)).fetchFilingDocument(any(), any(), anyString());
    }

    @Test
    void tickerAloneMeansLatestTenK() {
        StepVerifier.create(service.getSection(byTicker("AAPL", null, null, "MDA")))
                .assertNext(r -> {
                    assertThat(r.filing().accessionNumber()).isEqualTo(APPLE_10K_2025);
                    assertThat(r.text()).startsWith("Item 7. Management’s Discussion");
                })
                .verifyComplete();
    }

    @Test
    void tickerWithTenQReturnsQuarterlyMda() {
        StepVerifier.create(service.getSection(byTicker("AAPL", "10q", null, "MD&A")))
                .assertNext(r -> {
                    assertThat(r.filing().accessionNumber()).isEqualTo(APPLE_10Q_2026);
                    assertThat(r.filing().form()).isEqualTo("10-Q");
                    assertThat(r.text()).startsWith("Item 2. Management’s Discussion");
                })
                .verifyComplete();
    }

    @Test
    void fiscalYearSelectsThatYearsReport() {
        when(client.fetchFilingDocument(eq(APPLE), eq(AccessionNumber.parse("0000320193-24-000123")), anyString()))
                .thenReturn(Mono.fromCallable(() -> gunzip("aapl-20250927-10k.htm.gz")));

        StepVerifier.create(service.getSection(byTicker("AAPL", "10-K", 2024, "RISK_FACTORS")))
                .assertNext(r -> assertThat(r.filing().periodOfReport()).isEqualTo("2024-09-28"))
                .verifyComplete();
    }

    @Test
    void unavailableFiscalYearListsTheAvailableOnes() {
        StepVerifier.create(service.getSection(byTicker("AAPL", "10-K", 2010, "RISK_FACTORS")))
                .expectErrorMessage("No 10-K for fiscal year 2010 found for Apple Inc. (available fiscal years: "
                        + "2025, 2024, 2023).")
                .verify();
    }

    @Test
    void agentFiledAccessionWithoutTickerAsksForTicker() {
        Cik agent = new Cik(1193125);
        when(client.fetchSubmissions(agent)).thenReturn(Mono.just(new SubmissionsResponse("0001193125",
                "Donnelley Financial", List.of(), new SubmissionsResponse.Filings(new SubmissionsResponse.Recent(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of())))));

        StepVerifier.create(service.getSection(byAccession("0001193125-26-341032", "RISK_FACTORS", null, null)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("filing agent").hasMessageContaining("ticker"))
                .verify();
        verify(client, never()).fetchFilingDocument(any(), any(), anyString());
    }

    @Test
    void eightKAccessionIsRejected() {
        StepVerifier.create(service.getSection(byAccession("0000320193-26-000018", "MDA", null, null)))
                .expectErrorMessage("Filing 0000320193-26-000018 is a 8-K. Sections are only available for 10-K "
                        + "and 10-Q filings.")
                .verify();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "                     |      |      | RISK_FACTORS | 0  | 100   | Provide accession_number",
            "0000320193-25-000079 |      |      | BUSINESS     | 0  | 100   | section must be RISK_FACTORS or MDA",
            "0000320193-25-000079 |      |      | MDA          | -1 | 100   | offset must be 0 or greater",
            "0000320193-25-000079 |      |      | MDA          | 0  | 0     | limit must be between 1 and 20000",
            "0000320193-25-000079 |      |      | MDA          | 0  | 20001 | limit must be between 1 and 20000",
            "not-an-accession     |      |      | MDA          | 0  | 100   | Not a valid accession number",
            "                     | AAPL | 8-K  | MDA          | 0  | 100   | 8-K filings have no Risk Factors",
            "                     | AAPL | S-1  | MDA          | 0  | 100   | form must be 10-K or 10-Q",
            "                     | ZZZZ |      | MDA          | 0  | 100   | Unknown ticker 'ZZZZ'",
    })
    void rejectsBadInputWithActionableMessages(String accession, String ticker, String form, String section,
                                               int offset, int limit, String message) {
        StepVerifier.create(Mono.defer(() -> service.getSection(
                        new FilingSectionService.Request(accession, ticker, form, null, section, offset, limit))))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(message))
                .verify();
    }

    @Test
    void offsetPastEndIsRejectedButOffsetAtEndIsAnEmptyLastPage() {
        FilingMetadata meta = new FilingMetadata("1", "X", "X", "10-K", null, null, "a");

        assertThat(FilingSectionService.page(FilingSection.MDA, meta, "abcdef", 6, 10))
                .satisfies(p -> {
                    assertThat(p.text()).isEmpty();
                    assertThat(p.hasMore()).isFalse();
                });
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FilingSectionService.page(FilingSection.MDA, meta, "abcdef", 7, 10))
                .withMessageContaining("past the end of the section (total_chars 6)");
    }

    @Test
    void neverSplitsASurrogatePair() {
        FilingMetadata meta = new FilingMetadata("1", "X", "X", "10-K", null, null, "a");
        String text = "ab📈cd"; // chart emoji at index 2-3

        FilingSectionResult first = FilingSectionService.page(FilingSection.MDA, meta, text, 0, 3);

        assertThat(first.text()).isEqualTo("ab");
        assertThat(first.hasMore()).isTrue();
    }
}
