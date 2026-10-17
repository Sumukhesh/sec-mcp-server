package io.github.sumukhesh.secmcp.edgar;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The subset of {@code data.sec.gov/submissions/CIK##########.json} we use. EDGAR returns the
 * most recent filings as parallel arrays (one array per field, same index = same filing).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubmissionsResponse(String cik, String name, List<String> tickers, Filings filings) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Filings(Recent recent) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Recent(List<String> accessionNumber, List<String> filingDate, List<String> reportDate,
                         List<String> form, List<String> primaryDocument, List<String> primaryDocDescription) {
    }
}
