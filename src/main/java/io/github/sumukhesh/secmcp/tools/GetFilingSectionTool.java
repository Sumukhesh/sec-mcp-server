package io.github.sumukhesh.secmcp.tools;

import io.github.sumukhesh.secmcp.model.FilingSectionResult;
import io.github.sumukhesh.secmcp.parsing.FilingSection;
import io.github.sumukhesh.secmcp.parsing.FilingSectionService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Reads a section of a 10-K or 10-Q one page at a time.
 */
@Component
public class GetFilingSectionTool {

    private final FilingSectionService sectionService;

    public GetFilingSectionTool(FilingSectionService sectionService) {
        this.sectionService = sectionService;
    }

    @McpTool(name = "get_filing_section",
            title = "Read a 10-K or 10-Q section",
            description = """
                    Read the text of one section of a company's annual report (10-K) or quarterly report \
                    (10-Q): RISK_FACTORS (Item 1A, the company's own list of material risks) or MDA \
                    (Management's Discussion and Analysis: results, trends, liquidity and outlook). Use it \
                    to answer questions about a company's risks, performance drivers, or management's \
                    explanation of results.

                    Identify the filing in ONE of two ways:
                    1. accession_number from search_company results (also pass ticker; filings submitted \
                    by a filing agent cannot be located from the accession number alone), or
                    2. ticker plus optional form (10-K default, or 10-Q) and optional fiscal_year (the \
                    year the report's period ends; default is the latest filing).

                    Sections are long (often 50,000-100,000 characters), so text is returned in pages. \
                    Read the first page; if has_more is true and you need more, call again with the same \
                    arguments and offset = offset + returned_chars. Each page reports total_chars and the \
                    filing it came from (company, form, filing_date, period_of_report, accession_number) \
                    so you can cite it.

                    Example: get_filing_section(ticker="AAPL", section="RISK_FACTORS") -> {section: \
                    "RISK_FACTORS", filing: {company: "Apple Inc.", form: "10-K", period_of_report: \
                    "2025-09-27", accession_number: "0000320193-25-000079", ...}, text: "Item 1A. Risk \
                    Factors ...", offset: 0, returned_chars: 10000, total_chars: 67757, has_more: true}.""",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = true))
    // Parameter names are snake_case on purpose: they become the JSON argument names the model sees.
    public Mono<FilingSectionResult> getFilingSection(
            @McpToolParam(description = "Section to read: RISK_FACTORS or MDA.", required = true)
            FilingSection section,
            @McpToolParam(description = "Accession number from search_company, e.g. 0000320193-25-000079.",
                    required = false)
            String accession_number,
            @McpToolParam(description = "Company ticker, e.g. AAPL. Required unless accession_number is given; "
                    + "recommended with it.", required = false)
            String ticker,
            @McpToolParam(description = "10-K (default) or 10-Q. Used only when accession_number is not given.",
                    required = false)
            String form,
            @McpToolParam(description = "Fiscal year of the report, e.g. 2024 (the year its period ends). "
                    + "Default: latest. Used only when accession_number is not given.", required = false)
            Integer fiscal_year,
            @McpToolParam(description = "Character offset to start reading from. Default 0.", required = false)
            Integer offset,
            @McpToolParam(description = "Maximum characters to return, 1-20000. Default 10000.", required = false)
            Integer limit) {
        if (section == null) {
            return Mono.error(new IllegalArgumentException("section is required: RISK_FACTORS or MDA."));
        }
        return sectionService.getSection(new FilingSectionService.Request(accession_number, ticker, form,
                        fiscal_year, section.name(), offset, limit))
                .onErrorMap(e -> ToolErrors.toFriendly("get_filing_section", e));
    }
}
