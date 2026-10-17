package io.github.sumukhesh.secmcp.edgar;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One entry of {@code https://www.sec.gov/files/company_tickers.json}, which maps
 * "0", "1", ... to {@code {"cik_str": 320193, "ticker": "AAPL", "title": "Apple Inc."}}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CompanyTickerEntry(@JsonProperty("cik_str") long cik, String ticker, String title) {
}
