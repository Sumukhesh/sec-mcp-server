package io.github.sumukhesh.secmcp.parsing;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a named Item section in the plain text of a 10-K or 10-Q.
 *
 * <p>Item headings appear more than once: in the table of contents, in running page headers and
 * in cross-references ("see Item 1A. Risk Factors"). Every heading occurrence that starts a line
 * is a candidate start; each candidate ends at the next heading of any other Item. The candidate
 * with the longest span is the real section, because a table-of-contents entry spans only a few
 * lines until the next contents entry.
 */
public final class SectionExtractor {

    private static final String SEP = "\\s*[.:\\-\\u2013\\u2014]?\\s*";
    private static final String MANAGEMENTS_DISCUSSION = "management.{0,2}s?\\s+discussion";

    private record Bounds(Pattern start, Pattern end) {
        static Bounds of(String start, String end) {
            int flags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.MULTILINE;
            return new Bounds(Pattern.compile(start, flags), Pattern.compile(end, flags));
        }
    }

    private static final Map<FilingForm, Map<FilingSection, Bounds>> BOUNDS = Map.of(
            FilingForm.TEN_K, Map.of(
                    FilingSection.RISK_FACTORS, Bounds.of(
                            "^item\\s*1a" + SEP + "risk\\s+factors",
                            otherItem("1a")),
                    FilingSection.MDA, Bounds.of(
                            "^item\\s*7" + SEP + MANAGEMENTS_DISCUSSION,
                            otherItem("7"))),
            FilingForm.TEN_Q, Map.of(
                    FilingSection.RISK_FACTORS, Bounds.of(
                            "^item\\s*1a" + SEP + "risk\\s+factors",
                            otherItem("1a")),
                    FilingSection.MDA, Bounds.of(
                            "^item\\s*2" + SEP + MANAGEMENTS_DISCUSSION,
                            otherItem("2") + "|^part\\s+ii\\b")));

    /**
     * Heading of any Item other than {@code item}. Stopping at earlier Items too keeps a table of
     * contents entry short; ignoring the same Item keeps running page headers ("Item 1A") from
     * cutting the real section.
     */
    private static String otherItem(String item) {
        return "^item\\s*(?!" + item + "(?![a-c])\\b)\\d{1,2}[a-c]?\\b";
    }

    private SectionExtractor() {
    }

    /** Returns the section text, starting with its heading, or empty if the heading is not found. */
    public static Optional<String> extract(String text, FilingForm form, FilingSection section) {
        Bounds bounds = BOUNDS.get(form).get(section);
        Matcher start = bounds.start().matcher(text);
        Matcher end = bounds.end().matcher(text);

        int bestStart = -1;
        int bestEnd = -1;
        while (start.find()) {
            int from = start.start();
            int to = end.find(start.end()) ? end.start() : text.length();
            if (to - from > bestEnd - bestStart) {
                bestStart = from;
                bestEnd = to;
            }
        }
        return bestStart < 0 ? Optional.empty() : Optional.of(text.substring(bestStart, bestEnd).strip());
    }

    /** Filing forms that have Item-numbered sections. */
    public enum FilingForm {
        TEN_K, TEN_Q;

        public static Optional<FilingForm> fromSecForm(String form) {
            return switch (form) {
                case "10-K", "10-K/A", "10-KT" -> Optional.of(TEN_K);
                case "10-Q", "10-Q/A" -> Optional.of(TEN_Q);
                default -> Optional.empty();
            };
        }
    }
}
