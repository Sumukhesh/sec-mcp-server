package io.github.sumukhesh.secmcp.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Output of the {@code search_company} tool.
 *
 * @param company        best match for the query
 * @param recentFilings  up to 10 most recent 10-K, 10-Q and 8-K filings, newest first
 * @param otherMatches   other companies that also matched the query (empty when unambiguous)
 */
public record SearchCompanyResult(
        CompanyInfo company,
        @JsonProperty("recent_filings") List<FilingInfo> recentFilings,
        @JsonProperty("other_matches") List<CompanyInfo> otherMatches) {
}
