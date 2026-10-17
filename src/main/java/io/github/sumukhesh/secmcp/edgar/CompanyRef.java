package io.github.sumukhesh.secmcp.edgar;

/**
 * A listed company as known to the SEC ticker file.
 *
 * @param cik    SEC Central Index Key
 * @param ticker exchange ticker in SEC form (class shares use a dash, e.g. BRK-B)
 * @param name   company name as registered with the SEC
 */
public record CompanyRef(Cik cik, String ticker, String name) {
}
