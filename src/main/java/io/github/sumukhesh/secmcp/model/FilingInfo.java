package io.github.sumukhesh.secmcp.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One filing in a company's EDGAR history.
 *
 * @param form            SEC form type: 10-K (annual), 10-Q (quarterly) or 8-K (current event)
 * @param filingDate      date filed with the SEC (YYYY-MM-DD)
 * @param periodOfReport  period end the report covers (YYYY-MM-DD); for an 8-K, the event date
 * @param accessionNumber unique filing ID; pass it to get_filing_section
 */
public record FilingInfo(
        String form,
        @JsonProperty("filing_date") String filingDate,
        @JsonProperty("period_of_report") String periodOfReport,
        @JsonProperty("accession_number") String accessionNumber) {
}
