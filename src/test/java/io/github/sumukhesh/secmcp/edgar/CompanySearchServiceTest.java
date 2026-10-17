package io.github.sumukhesh.secmcp.edgar;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import io.github.sumukhesh.secmcp.model.CompanyInfo;
import io.github.sumukhesh.secmcp.model.FilingInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanySearchServiceTest {

    private static final CompanyRef APPLE = new CompanyRef(new Cik(320193), "AAPL", "Apple Inc.");
    private static final CompanyRef APPLIED = new CompanyRef(new Cik(6951), "AMAT", "APPLIED MATERIALS INC /DE");

    private final TickerCikResolver resolver = mock(TickerCikResolver.class);
    private final EdgarClient client = mock(EdgarClient.class);
    private final CompanySearchService service = new CompanySearchService(resolver, client);
    private SubmissionsResponse appleSubmissions;

    @BeforeEach
    void setUp() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/edgar/submissions-CIK0000320193.json")) {
            appleSubmissions = JsonMapper.builder().build().readValue(in, SubmissionsResponse.class);
        }
        when(resolver.allCompanies()).thenReturn(Mono.just(List.of(APPLE, APPLIED)));
        when(resolver.findByTicker(anyString())).thenReturn(Mono.just(Optional.empty()));
        when(resolver.findByTicker("AAPL")).thenReturn(Mono.just(Optional.of(APPLE)));
        when(client.fetchSubmissions(new Cik(320193))).thenReturn(Mono.just(appleSubmissions));
    }

    @Test
    void returnsCompanyAndTenMostRecentCoreFilings() {
        StepVerifier.create(service.search("AAPL"))
                .assertNext(r -> {
                    assertThat(r.company()).isEqualTo(new CompanyInfo("0000320193", "Apple Inc.", "AAPL"));
                    assertThat(r.recentFilings()).hasSize(10)
                            .allSatisfy(f -> assertThat(f.form()).isIn("10-K", "10-Q", "8-K"))
                            .isSortedAccordingTo((a, b) -> b.filingDate().compareTo(a.filingDate()));
                    assertThat(r.recentFilings()).extracting(FilingInfo::accessionNumber)
                            .allSatisfy(a -> assertThat(a).matches("\\d{10}-\\d{2}-\\d{6}"));
                    assertThat(r.otherMatches()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void periodOfReportIsSetForPeriodicReports() {
        StepVerifier.create(service.search("AAPL"))
                .assertNext(r -> assertThat(r.recentFilings())
                        .filteredOn(f -> !f.form().equals("8-K"))
                        .allSatisfy(f -> assertThat(f.periodOfReport()).matches("\\d{4}-\\d{2}-\\d{2}")))
                .verifyComplete();
    }

    @Test
    void nameSearchListsOtherMatches() {
        StepVerifier.create(service.search("appl"))
                .assertNext(r -> {
                    assertThat(r.company().ticker()).isEqualTo("AAPL");
                    assertThat(r.otherMatches()).extracting(CompanyInfo::ticker).containsExactly("AMAT");
                })
                .verifyComplete();
    }

    @Test
    void unknownCompanyGivesActionableErrorWithoutCallingSubmissions() {
        StepVerifier.create(service.search("QWXZQ"))
                .expectErrorSatisfies(e -> assertThat(e)
                        .isInstanceOf(CompanySearchService.CompanyNotFoundException.class)
                        .hasMessage("No company found for 'QWXZQ'. Try the ticker symbol (e.g. AAPL) or the company's "
                                + "registered name (e.g. 'Apple'). Only SEC-registered, exchange-listed companies "
                                + "are searchable."))
                .verify();
        verify(client, never()).fetchSubmissions(any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankQueryIsRejected(String query) {
        StepVerifier.create(service.search(query))
                .expectErrorMessage("Provide a company name or ticker, for example 'Apple' or 'AAPL'.")
                .verify();
    }

    @Test
    void overlongQueryIsRejected() {
        StepVerifier.create(service.search("a".repeat(101)))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void toleratesMissingOrRaggedFilingArrays() {
        SubmissionsResponse ragged = new SubmissionsResponse("1", "X", List.of(), new SubmissionsResponse.Filings(
                new SubmissionsResponse.Recent(List.of("0000000001-24-000001", "0000000001-24-000002"),
                        List.of("2024-01-02"), null, List.of("10-K", "10-Q"), null, null)));

        assertThat(CompanySearchService.recentCoreFilings(ragged, 10))
                .containsExactly(new FilingInfo("10-K", "2024-01-02", null, "0000000001-24-000001"));
        assertThat(CompanySearchService.recentCoreFilings(
                new SubmissionsResponse("1", "X", null, null), 10)).isEmpty();
    }
}
