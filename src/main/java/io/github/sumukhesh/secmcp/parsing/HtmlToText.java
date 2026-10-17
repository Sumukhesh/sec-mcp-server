package io.github.sumukhesh.secmcp.parsing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/**
 * Converts an EDGAR filing (HTML or inline XBRL) into readable plain text: one paragraph or
 * table row per line, hidden XBRL metadata, scripts and styles removed, and page furniture
 * (page numbers, running headers/footers, "Table of Contents" links) dropped.
 */
public final class HtmlToText {

    private static final Set<String> BLOCK_TAGS = Set.of(
            "p", "div", "br", "tr", "li", "ul", "ol", "table", "h1", "h2", "h3", "h4", "h5", "h6",
            "section", "article", "blockquote", "pre", "hr", "center", "dl", "dt", "dd", "body");
    private static final Set<String> CELL_TAGS = Set.of("td", "th");

    private static final Pattern HORIZONTAL_SPACE = Pattern.compile("[ \\t\\x0B\\f\\r\\u00A0\\u2007\\u202F\\u200B]+");
    private static final Pattern PAGE_NUMBER = Pattern.compile("(?i)(page\\s+)?[ivxlc]{1,6}|\\d{1,3}|[a-z]-?\\d{1,3}");
    private static final Pattern TOC_LINK = Pattern.compile("(?i)(back\\s+to\\s+)?table\\s+of\\s+contents|index\\s+to\\s+financial\\s+statements");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    /** A short line seen this often (ignoring digits) is a running header/footer, e.g. "Apple Inc. | 2025 Form 10-K | 17". */
    private static final int RUNNING_LINE_MIN_REPEATS = 8;
    private static final int RUNNING_LINE_MAX_LENGTH = 80;

    private HtmlToText() {
    }

    public static String convert(String html) {
        Document doc = Jsoup.parse(html);
        doc.select("script, style, noscript, head, ix|header").remove();
        doc.select("[style]").stream()
                .filter(e -> e.attr("style").replace(" ", "").toLowerCase().contains("display:none"))
                .forEach(Element::remove);

        StringBuilder out = new StringBuilder(html.length() / 4);
        NodeTraversor.traverse(new TextCollector(out), doc.body() != null ? doc.body() : doc);
        return cleanLines(out.toString());
    }

    static String cleanLines(String raw) {
        List<String> lines = new ArrayList<>();
        for (String line : raw.split("\n", -1)) {
            lines.add(HORIZONTAL_SPACE.matcher(line).replaceAll(" ").strip());
        }

        Map<String, Integer> shapeCounts = new HashMap<>();
        for (String line : lines) {
            if (!line.isEmpty() && line.length() <= RUNNING_LINE_MAX_LENGTH && !isTableRow(line)) {
                shapeCounts.merge(shape(line), 1, Integer::sum);
            }
        }

        StringBuilder text = new StringBuilder();
        boolean pendingBlank = false;
        for (String line : lines) {
            if (line.isEmpty()) {
                pendingBlank = !text.isEmpty();
                continue;
            }
            if (PAGE_NUMBER.matcher(line).matches() || TOC_LINK.matcher(line).matches()) {
                continue;
            }
            if (line.length() <= RUNNING_LINE_MAX_LENGTH && !isTableRow(line)
                    && shapeCounts.getOrDefault(shape(line), 0) >= RUNNING_LINE_MIN_REPEATS) {
                continue;
            }
            if (pendingBlank) {
                text.append('\n');
                pendingBlank = false;
            }
            text.append(line).append('\n');
        }
        return text.toString().strip();
    }

    /** Financial table rows legitimately repeat ("Total ... $ 1,234"); never treat them as page furniture. */
    private static boolean isTableRow(String line) {
        return line.contains("$") || line.contains("%");
    }

    private static String shape(String line) {
        return DIGITS.matcher(line.toLowerCase()).replaceAll("#");
    }

    /**
     * Paragraph-level blocks are separated by a blank line and each table row is one line. Inside a
     * table cell, blocks are treated as inline so a row such as "Item 1A. | Risk Factors | 5" or
     * "Total net sales | $ | 1,234" stays on a single line.
     */
    private static final class TextCollector implements NodeVisitor {
        private final StringBuilder out;
        private int cellDepth;

        TextCollector(StringBuilder out) {
            this.out = out;
        }

        @Override
        public void head(Node node, int depth) {
            if (node instanceof TextNode t) {
                out.append(t.getWholeText().replace('\n', ' '));
            } else if (node instanceof Element e) {
                String tag = e.normalName();
                if (CELL_TAGS.contains(tag)) {
                    cellDepth++;
                    out.append(' ');
                } else if (cellDepth > 0 && (BLOCK_TAGS.contains(tag) || tag.equals("tr"))) {
                    out.append(' ');
                } else if (BLOCK_TAGS.contains(tag) || tag.equals("tr")) {
                    startLine();
                }
            }
        }

        @Override
        public void tail(Node node, int depth) {
            if (node instanceof Element e) {
                String tag = e.normalName();
                if (CELL_TAGS.contains(tag)) {
                    cellDepth = Math.max(0, cellDepth - 1);
                    out.append(' ');
                } else if (cellDepth > 0 && (BLOCK_TAGS.contains(tag) || tag.equals("tr"))) {
                    out.append(' ');
                } else if (tag.equals("tr")) {
                    out.append('\n');
                } else if (BLOCK_TAGS.contains(tag)) {
                    out.append("\n\n");
                }
            }
        }

        private void startLine() {
            if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
        }
    }
}
