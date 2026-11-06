package com.pdfgen.domain.overlay;

import java.util.Arrays;
import java.util.List;

/**
 * Address of a node in a document, made of ids only: {@code sectionId[/itemId[/bulletId]]}.
 * The empty path addresses the document itself (its list of sections).
 */
public record IdPath(List<String> segments) {

    private static final IdPath ROOT = new IdPath(List.of());

    public IdPath {
        segments = List.copyOf(segments);
    }

    public static IdPath root() {
        return ROOT;
    }

    /**
     * Parses {@code "experience/acme/b1"}. Blank or {@code "sections"} means the document root.
     *
     * @throws IllegalArgumentException for empty segments such as {@code "a//b"}
     */
    public static IdPath parse(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty() || trimmed.equals("sections")) {
            return ROOT;
        }
        List<String> parts = Arrays.stream(trimmed.split("/", -1)).map(String::trim).toList();
        if (parts.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("invalid path '" + raw + "': empty segment");
        }
        return new IdPath(parts);
    }

    public boolean isRoot() {
        return segments.isEmpty();
    }

    public int depth() {
        return segments.size();
    }

    public String first() {
        return segments.getFirst();
    }

    public String last() {
        return segments.getLast();
    }

    public IdPath parent() {
        return isRoot() ? this : new IdPath(segments.subList(0, segments.size() - 1));
    }

    public IdPath tail() {
        return isRoot() ? this : new IdPath(segments.subList(1, segments.size()));
    }

    @Override
    public String toString() {
        return isRoot() ? "sections" : String.join("/", segments);
    }
}
