package io.github.sumukhesh.secmcp.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Identifies the filing a section was taken from, so answers can cite their source.
 *
 * @param cik             10-digit, zero-padded SEC Central Index Key of the company
 * @param company         official company name
 * @param ticker          exchange ticker, or null if the company has none
 * @param form            SEC form type, e.g. 10-K or 10-Q
 * @param filingDate      date filed with the SEC (YYYY-MM-DD)
 * @param periodOfReport  fiscal period end the report covers (YYYY-MM-DD)
 * @param accessionNumber unique filing ID
 */
public record FilingMetadata(
        String cik,
        String company,
        String ticker,
        String form,
        @JsonProperty("filing_date") String filingDate,
        @JsonProperty("period_of_report") String periodOfReport,
        @JsonProperty("accession_number") String accessionNumber) {
}
