package io.github.sumukhesh.secmcp.tools;

import io.github.sumukhesh.secmcp.edgar.CompanySearchService;
import io.github.sumukhesh.secmcp.model.SearchCompanyResult;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Entry point for research: turns a name or ticker into a CIK and recent filing list.
 */
@Component
public class SearchCompanyTool {

    private final CompanySearchService searchService;

    public SearchCompanyTool(CompanySearchService searchService) {
        this.searchService = searchService;
    }

    @McpTool(name = "search_company",
            title = "Search company filings",
            description = """
                    Find a US public company in SEC EDGAR and list its most recent filings. \
                    Call this FIRST whenever a question is about a specific company: it returns the \
                    accession numbers that other tools need.

                    Input: 'query' is a ticker (AAPL, BRK.B), a company name (Apple, "Bank of America") \
                    or a numeric SEC CIK (320193). Write tickers in UPPERCASE; lowercase text is matched \
                    against company names first.

                    Output: the best-matching company {cik, name, ticker}; recent_filings, the 10 most \
                    recent 10-K (annual report), 10-Q (quarterly report) and 8-K (current event) filings, \
                    newest first, each with form, filing_date, period_of_report and accession_number; and \
                    other_matches, other companies that matched a partial name. If other_matches is not \
                    empty and the company looks wrong, call again with the right ticker.

                    Example: search_company(query="Apple") -> company {cik:"0000320193", name:"Apple Inc.", \
                    ticker:"AAPL"}, recent_filings [{form:"10-K", filing_date:"2025-10-31", \
                    period_of_report:"2025-09-27", accession_number:"0000320193-25-000079"}, ...].

                    Only the 10 newest core filings are returned; older filings are not listed.""",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = true))
    public Mono<SearchCompanyResult> searchCompany(
            @McpToolParam(description = "Ticker (e.g. AAPL), company name (e.g. Apple) or SEC CIK (e.g. 320193). "
                    + "1-100 characters.", required = true)
            String query) {
        return searchService.search(query)
                .onErrorMap(e -> ToolErrors.toFriendly("search_company", e));
    }
}
