package io.github.sumukhesh.secmcp.parsing;

/**
 * Sections that can be extracted from 10-K and 10-Q filings.
 */
public enum FilingSection {

    /** 10-K Part I Item 1A; 10-Q Part II Item 1A. */
    RISK_FACTORS("Risk Factors"),

    /** Management's Discussion and Analysis: 10-K Part II Item 7; 10-Q Part I Item 2. */
    MDA("Management's Discussion and Analysis");

    private final String title;

    FilingSection(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
