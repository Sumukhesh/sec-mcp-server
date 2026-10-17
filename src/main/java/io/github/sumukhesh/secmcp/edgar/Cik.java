package io.github.sumukhesh.secmcp.edgar;

/**
 * SEC Central Index Key. Stored as a number; rendered zero-padded to 10 digits where EDGAR's
 * data.sec.gov endpoints require it (e.g. {@code CIK0000320193.json}).
 */
public record Cik(long value) {

    private static final long MAX = 9_999_999_999L;

    public Cik {
        if (value <= 0 || value > MAX) {
            throw new IllegalArgumentException("CIK must be between 1 and 10 digits, got " + value);
        }
    }

    /** Parses "320193", "0000320193" or "CIK0000320193". */
    public static Cik parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("CIK is required");
        }
        String digits = raw.strip();
        if (digits.regionMatches(true, 0, "CIK", 0, 3)) {
            digits = digits.substring(3);
        }
        if (digits.isEmpty() || digits.length() > 10 || !digits.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Not a valid CIK: '" + raw + "'. A CIK has up to 10 digits.");
        }
        return new Cik(Long.parseLong(digits));
    }

    /** Ten-digit, zero-padded form used by data.sec.gov. */
    public String padded() {
        return String.format("%010d", value);
    }

    /** Unpadded form used in /Archives/edgar/data/ paths. */
    @Override
    public String toString() {
        return Long.toString(value);
    }
}
