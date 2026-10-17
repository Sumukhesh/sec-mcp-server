package io.github.sumukhesh.secmcp.edgar;

import java.util.regex.Pattern;

/**
 * EDGAR accession number, e.g. {@code 0000320193-24-000123}: filer ID, two-digit year, sequence.
 * Validated strictly because it becomes part of an Archives URL path.
 */
public record AccessionNumber(String value) {

    private static final Pattern DASHED = Pattern.compile("\\d{10}-\\d{2}-\\d{6}");
    private static final Pattern UNDASHED = Pattern.compile("\\d{18}");

    public AccessionNumber {
        if (value == null || !DASHED.matcher(value).matches()) {
            throw new IllegalArgumentException("Accession number must look like 0000320193-24-000123");
        }
    }

    /** Accepts the dashed form or the 18-digit form used in Archives folder names. */
    public static AccessionNumber parse(String raw) {
        String s = raw == null ? "" : raw.strip();
        if (UNDASHED.matcher(s).matches()) {
            s = s.substring(0, 10) + "-" + s.substring(10, 12) + "-" + s.substring(12);
        }
        if (!DASHED.matcher(s).matches()) {
            throw new IllegalArgumentException("Not a valid accession number: '" + raw
                    + "'. Expected a value like 0000320193-24-000123 (from search_company results).");
        }
        return new AccessionNumber(s);
    }

    /** Folder name under /Archives/edgar/data/{cik}/. */
    public String withoutDashes() {
        return value.replace("-", "");
    }

    @Override
    public String toString() {
        return value;
    }
}
