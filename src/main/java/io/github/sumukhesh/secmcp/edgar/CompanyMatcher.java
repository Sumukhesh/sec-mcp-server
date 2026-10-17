package io.github.sumukhesh.secmcp.edgar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Ranks companies against a free-text query (ticker, CIK or name). Pure function, no I/O.
 *
 * <p>Ranking, best first: exact ticker / CIK, exact name, name starts with the query on a word
 * boundary, query appears as whole words, name starts with the query mid-word, substring.
 * Ties keep SEC file order, which is roughly by market cap, so "meta" prefers Meta Platforms.
 *
 * <p>Ticker vs name: an all-uppercase query ("FORD") is treated as a ticker first; a query with
 * lowercase letters ("ford") prefers a company name match (Ford Motor) over a ticker that
 * happens to spell the same word (FORD = Forward Industries).
 */
public final class CompanyMatcher {

    private static final Set<String> SUFFIXES = Set.of(
            "inc", "incorporated", "corp", "corporation", "co", "company", "ltd", "limited", "plc",
            "llc", "lp", "sa", "nv", "ag", "se", "holdings", "group");
    // SEC name tags such as "/DE/", "/DE", "/NEW", "/UK", "/ADR"
    private static final Pattern STATE_TAG = Pattern.compile("/[a-z]{2,4}\\b/?");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9 ]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private static final int EXACT_ID = 100;
    private static final int EXACT_NAME = 90;
    private static final int WORD_PREFIX = 70;
    private static final int WHOLE_WORDS = 60;
    private static final int TICKER_FOR_LOWERCASE_QUERY = 50;
    private static final int MID_WORD_PREFIX = 30;
    private static final int SUBSTRING = 20;

    private CompanyMatcher() {
    }

    public record Match(CompanyRef company, int score) {
    }

    /**
     * Returns matches best-first, one per CIK, at most {@code limit}.
     *
     * @param companies one entry per company, in SEC order
     * @param tickerHit company whose ticker exactly equals the query, if any
     */
    public static List<Match> rank(String query, List<CompanyRef> companies, Optional<CompanyRef> tickerHit, int limit) {
        String q = query.strip();
        String nq = normalizeName(q);
        boolean looksLikeTicker = q.equals(q.toUpperCase(Locale.ROOT));
        Optional<Cik> cikQuery = parseCik(q);

        Map<Cik, Match> best = new LinkedHashMap<>();
        tickerHit.ifPresent(c -> best.put(c.cik(), new Match(c, looksLikeTicker ? EXACT_ID : TICKER_FOR_LOWERCASE_QUERY)));

        for (CompanyRef c : companies) {
            int score = cikQuery.filter(c.cik()::equals).isPresent() ? EXACT_ID : nameScore(nq, normalizeName(c.name()));
            if (score > 0) {
                best.merge(c.cik(), new Match(c, score), (a, b) -> a.score() >= b.score() ? a : b);
            }
        }

        List<Match> sorted = new ArrayList<>(best.values());
        // Stable sort keeps insertion (SEC) order among equal scores.
        sorted.sort(Comparator.comparingInt(Match::score).reversed());
        // Once there is a real word-level match, bare substrings ("apple" in "Pineapple") are noise.
        if (!sorted.isEmpty() && sorted.getFirst().score() >= WHOLE_WORDS) {
            sorted.removeIf(m -> m.score() <= SUBSTRING);
        }
        return List.copyOf(sorted.subList(0, Math.min(limit, sorted.size())));
    }

    static int nameScore(String query, String name) {
        if (query.length() < 2 || name.isEmpty()) {
            return 0;
        }
        if (name.equals(query)) {
            return EXACT_NAME;
        }
        if (name.startsWith(query + " ")) {
            return WORD_PREFIX;
        }
        if ((" " + name + " ").contains(" " + query + " ")) {
            return WHOLE_WORDS;
        }
        if (query.length() >= 3 && name.startsWith(query)) {
            return MID_WORD_PREFIX;
        }
        if (query.length() >= 4 && name.contains(query)) {
            return SUBSTRING;
        }
        return 0;
    }

    /** "Apple Inc." -> "apple", "AMAZON COM INC" -> "amazon com", "AT&T INC." -> "at and t". */
    static String normalizeName(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).replace("&", " and ");
        s = STATE_TAG.matcher(s).replaceAll(" ");
        s = NON_ALNUM.matcher(s).replaceAll(" ");
        List<String> words = new ArrayList<>(List.of(SPACES.split(s.strip())));
        words.removeIf(String::isEmpty);
        if (!words.isEmpty() && words.getFirst().equals("the")) {
            words.removeFirst();
        }
        while (words.size() > 1 && SUFFIXES.contains(words.getLast())) {
            words.removeLast();
        }
        return String.join(" ", words);
    }

    private static Optional<Cik> parseCik(String q) {
        String digits = q.regionMatches(true, 0, "CIK", 0, 3) ? q.substring(3) : q;
        if (digits.isEmpty() || digits.length() > 10 || !digits.chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Cik.parse(digits));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
