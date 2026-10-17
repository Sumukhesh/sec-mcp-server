package io.github.sumukhesh.secmcp.model;

/**
 * A company identified in EDGAR.
 *
 * @param cik    10-digit, zero-padded SEC Central Index Key
 * @param name   official name registered with the SEC
 * @param ticker exchange ticker (class shares use a dash, e.g. BRK-B)
 */
public record CompanyInfo(String cik, String name, String ticker) {
}
