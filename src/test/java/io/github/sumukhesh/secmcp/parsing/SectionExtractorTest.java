package io.github.sumukhesh.secmcp.parsing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

import io.github.sumukhesh.secmcp.parsing.SectionExtractor.FilingForm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs extraction on real filings saved from EDGAR (gzipped) plus a synthetic filing with a
 * table-based contents page, running page headers and an in-body cross-reference.
 */
class SectionExtractorTest {

    private static final Map<String, String> TEXT_CACHE = new ConcurrentHashMap<>();

    static String filingText(String fixture) {
        return TEXT_CACHE.computeIfAbsent(fixture, f -> {
            try (InputStream raw = SectionExtractorTest.class.getResourceAsStream("/fixtures/filings/" + f);
                 InputStream in = f.endsWith(".gz") ? new GZIPInputStream(raw) : raw) {
                return HtmlToText.convert(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @ParameterizedTest(name = "{0} {2}")
    @CsvSource(delimiter = '|', value = {
            // fixture                    | form  | section      | heading starts with                    | ends with                                              | min chars
            "aapl-20250927-10k.htm.gz | TEN_K | RISK_FACTORS | Item 1A. Risk Factors                  | investor confidence and employee retention.            | 50000",
            "aapl-20250927-10k.htm.gz | TEN_K | MDA          | Item 7. Management’s Discussion        | the Company’s financial condition and operating results. | 10000",
            "msft-20260630-10k.htm.gz | TEN_K | RISK_FACTORS | ITEM 1A. RISK FACTORS                  | new relationships with worker representatives.         | 50000",
            "msft-20260630-10k.htm.gz | TEN_K | MDA          | ITEM 7. MANAGEMENT’S DISCUSSION        | Corporate Vice President and Chief Accounting Officer  | 30000",
            "aapl-20260627-10q.htm.gz | TEN_Q | RISK_FACTORS | Item 1A. Risk Factors                  | legal liability.                                       | 10000",
            "aapl-20260627-10q.htm.gz | TEN_Q | MDA          | Item 2. Management’s Discussion        | since the 2025 Form 10-K.                              | 10000",
    })
    void extractsSectionsFromRealFilings(String fixture, FilingForm form, FilingSection section,
                                         String heading, String ending, int minChars) {
        String text = SectionExtractor.extract(filingText(fixture), form, section).orElseThrow();

        assertThat(text).startsWith(heading).endsWith(ending).hasSizeGreaterThan(minChars);
    }

    @Test
    void realSectionsDoNotIncludeTheNextItem() {
        String rf = SectionExtractor.extract(filingText("aapl-20250927-10k.htm.gz"), FilingForm.TEN_K,
                FilingSection.RISK_FACTORS).orElseThrow();
        String qRf = SectionExtractor.extract(filingText("aapl-20260627-10q.htm.gz"), FilingForm.TEN_Q,
                FilingSection.RISK_FACTORS).orElseThrow();

        assertThat(rf).doesNotContain("Unresolved Staff Comments");
        assertThat(qRf).doesNotContain("Unregistered Sales of Equity Securities")
                .doesNotContain("Financial Statements");
    }

    @Test
    void messyTableOfContentsPicksTheBodySection() {
        String text = filingText("messy-toc-10k.htm");

        String rf = SectionExtractor.extract(text, FilingForm.TEN_K, FilingSection.RISK_FACTORS).orElseThrow();
        assertThat(rf).startsWith("ITEM 1A — RISK FACTORS")
                .contains("FIRST-RISK-MARKER", "See Item 7 for liquidity details", "LAST-RISK-MARKER")
                .doesNotContain("UNRESOLVED STAFF COMMENTS");

        String mda = SectionExtractor.extract(text, FilingForm.TEN_K, FilingSection.MDA).orElseThrow();
        assertThat(mda).startsWith("Item 7. Management's Discussion")
                .contains("MDA-MARKER", "Total net sales $ 1,234")
                .doesNotContain("Rates moved");
    }

    @Test
    void missingSectionIsEmpty() {
        assertThat(SectionExtractor.extract("Item 1. Business\nWe sell things.", FilingForm.TEN_K,
                FilingSection.RISK_FACTORS)).isEmpty();
    }

    @Test
    void mapsSecFormsToExtractableForms() {
        assertThat(FilingForm.fromSecForm("10-K")).contains(FilingForm.TEN_K);
        assertThat(FilingForm.fromSecForm("10-K/A")).contains(FilingForm.TEN_K);
        assertThat(FilingForm.fromSecForm("10-Q")).contains(FilingForm.TEN_Q);
        assertThat(FilingForm.fromSecForm("8-K")).isEmpty();
    }
}
