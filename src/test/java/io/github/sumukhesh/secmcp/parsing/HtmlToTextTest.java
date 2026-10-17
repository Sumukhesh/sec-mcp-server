package io.github.sumukhesh.secmcp.parsing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlToTextTest {

    @Test
    void dropsHiddenXbrlScriptsStylesAndPageFurniture() {
        String text = SectionExtractorTest.filingText("messy-toc-10k.htm");

        assertThat(text)
                .doesNotContain("HIDDEN XBRL FACT", "shouldNotAppear", "margin", "Synthetic 10-K")
                .doesNotContainPattern("(?m)^\\d{1,3}$")              // page numbers
                .doesNotContainIgnoringCase("table of contents\n");   // TOC back-links (heading kept as bold text)
    }

    @Test
    void putsBlocksAndTableRowsOnTheirOwnLines() {
        String text = HtmlToText.convert("""
                <p>First&nbsp;paragraph   with   spaces.</p><div>Second</div>
                <table><tr><td>Revenue</td><td>$</td><td>1,000</td></tr><tr><td>Cost</td><td>$</td><td>400</td></tr></table>
                <p></p><p></p><p>After blank lines</p>""");

        assertThat(text).isEqualTo("""
                First paragraph with spaces.

                Second

                Revenue $ 1,000
                Cost $ 400

                After blank lines""");
    }

    @Test
    void removesRunningFootersButKeepsRepeatedFinancialRows() {
        String[] topics = {"supply", "demand", "pricing", "margins", "taxes", "leases", "debt", "cash", "risk", "growth"};
        StringBuilder html = new StringBuilder();
        for (int page = 1; page <= 10; page++) {
            html.append("<p>This page discusses ").append(topics[page - 1]).append(" in detail.</p>")
                    .append("<p>Total net sales $ 100</p>")
                    .append("<p>Apple Inc. | 2025 Form 10-K | ").append(page).append("</p>");
        }

        String text = HtmlToText.convert(html.toString());

        assertThat(text).doesNotContain("Form 10-K |");
        assertThat(text.split("Total net sales", -1)).hasSize(11);
        assertThat(text).contains("This page discusses supply in detail.", "This page discusses growth in detail.");
    }

    @Test
    void realTenKShrinksToReadableText() {
        String text = SectionExtractorTest.filingText("aapl-20250927-10k.htm.gz");

        assertThat(text).hasSizeBetween(150_000, 300_000)
                .doesNotContain("<div", "ix:nonFraction", "Apple Inc. | 2025 Form 10-K |")
                .contains("Item 1A. Risk Factors");
    }
}
