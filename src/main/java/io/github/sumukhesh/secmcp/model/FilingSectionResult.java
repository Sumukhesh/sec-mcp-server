package io.github.sumukhesh.secmcp.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One page of a filing section.
 *
 * @param section       section returned, e.g. RISK_FACTORS
 * @param filing        the filing the text comes from
 * @param text          section text for this page
 * @param offset        character offset of this page within the section
 * @param returnedChars number of characters in {@code text}
 * @param totalChars    length of the whole section in characters
 * @param hasMore       true if more text follows; call again with offset = offset + returned_chars
 */
public record FilingSectionResult(
        String section,
        FilingMetadata filing,
        String text,
        int offset,
        @JsonProperty("returned_chars") int returnedChars,
        @JsonProperty("total_chars") int totalChars,
        @JsonProperty("has_more") boolean hasMore) {
}
