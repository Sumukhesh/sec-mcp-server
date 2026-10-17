package io.github.sumukhesh.secmcp.edgar;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class CompanyMatcherTest {

    /** Real SEC ticker fixture, plus two companies that make "ford" ambiguous. */
    private static List<CompanyRef> companies;
    private static Map<String, CompanyRef> byTicker;

    @BeforeAll
    static void loadFixture() throws Exception {
        try (InputStream in = CompanyMatcherTest.class.getResourceAsStream("/fixtures/edgar/company_tickers.json")) {
            Map<String, CompanyTickerEntry> raw = JsonMapper.builder().build().readValue(in, new TypeReference<>() { });
            raw.put("9998", new CompanyTickerEntry(37996, "F", "FORD MOTOR CO"));
            raw.put("9999", new CompanyTickerEntry(38264, "FORD", "FORWARD INDUSTRIES INC"));
            raw.put("10000", new CompanyTickerEntry(1000001, "PAPL", "Pineapple Financial Inc."));
            raw.put("10001", new CompanyTickerEntry(1000002, "APLE", "Apple Hospitality REIT, Inc."));
            raw.put("10002", new CompanyTickerEntry(1000003, "HIG", "HARTFORD INSURANCE GROUP, INC."));
            TickerCikResolver.Index index = TickerCikResolver.buildIndex(raw);
            companies = List.copyOf(index.byCik().values());
            byTicker = index.byTicker();
        }
    }

    private static List<CompanyMatcher.Match> rank(String query) {
        Optional<CompanyRef> tickerHit = TickerCikResolver.normalizeTicker(query).map(byTicker::get);
        return CompanyMatcher.rank(query, companies, tickerHit, 5);
    }

    private static String top(String query) {
        List<CompanyMatcher.Match> matches = rank(query);
        return matches.isEmpty() ? null : matches.getFirst().company().ticker();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "AAPL, AAPL", "aapl, AAPL", "Apple, AAPL", "apple inc., AAPL",
            "Microsoft, MSFT", "microsoft corporation, MSFT",
            "Amazon, AMZN", "amazon.com, AMZN",
            "meta, META", "Meta Platforms, META",
            "berkshire, BRK-B", "BRK.B, BRK-B", "BRK.A, BRK-A",   // explicit share class is kept
            "alphabet, GOOGL", "GOOG, GOOG",
            "Eli Lilly, LLY", "johnson & johnson, JNJ", "coca-cola, KO", "Coca Cola, KO",
            "Bank of America, BAC", "applied materials, AMAT", "Costco, COST",
            "320193, AAPL", "CIK0000320193, AAPL",
            "ford, F", "Ford Motor, F", "FORD, FORD", "F, F",
    })
    void picksTheExpectedCompany(String query, String expectedTicker) {
        assertThat(top(query)).isEqualTo(expectedTicker);
    }

    @Test
    void wholeWordMatchOutranksPartialWordThenSecOrder() {
        // "Advanced Micro Devices" contains the word; "Microsoft" and "Micron" only start with it.
        assertThat(rank("micro")).extracting(m -> m.company().ticker())
                .containsExactly("AMD", "MSFT", "MU");
    }

    @Test
    void lowercaseTickerCollisionStillOffersTheTickerCompany() {
        assertThat(rank("ford")).extracting(m -> m.company().ticker()).containsExactly("F", "FORD");
    }

    @Test
    void dropsBareSubstringMatchesWhenAWordMatchExists() {
        assertThat(rank("Apple")).extracting(m -> m.company().ticker()).containsExactly("AAPL", "APLE");
        assertThat(rank("ford")).extracting(m -> m.company().ticker()).doesNotContain("HIG");
    }

    @Test
    void keepsSubstringMatchesWhenNothingBetterExists() {
        assertThat(rank("artford")).extracting(m -> m.company().ticker()).containsExactly("HIG");
    }

    @Test
    void returnsOneEntryPerCompany() {
        assertThat(rank("alphabet")).extracting(m -> m.company().cik()).doesNotHaveDuplicates();
    }

    @Test
    void noMatchIsEmpty() {
        assertThat(rank("zzzz not a company")).isEmpty();
        assertThat(rank("x")).isEmpty();
    }

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @CsvSource(delimiter = '|', value = {
            "Apple Inc.|apple", "AMAZON COM INC|amazon com", "BANK OF AMERICA CORP /DE/|bank of america",
            "APPLIED MATERIALS INC /DE|applied materials", "COSTCO WHOLESALE CORP /NEW|costco wholesale",
            "JOHNSON & JOHNSON|johnson and johnson", "The Home Depot, Inc.|home depot",
            "Contemporary Amperex Technology Co., Limited/ADR|contemporary amperex technology",
            "Inc.|inc",
    })
    void normalizesSecNames(String raw, String expected) {
        assertThat(CompanyMatcher.normalizeName(raw)).isEqualTo(expected);
    }
}
