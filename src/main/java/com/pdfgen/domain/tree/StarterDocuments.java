package com.pdfgen.domain.tree;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The skeleton of a new document created from scratch in the editor (ADR-0024, ADR-0026): the usual sections
 * with no items, so the user fills them in instead of deleting placeholder text. No {@code outputName}: the PDF is then
 * named after the document id.
 */
public final class StarterDocuments {

    private StarterDocuments() {
    }

    /** A new CV, frozen (unmodifiable, insertion-ordered) like a tree loaded from a file. */
    public static Map<String, Object> cv() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("type", "cv");
        meta.put("lang", "en");
        meta.put("theme", "classic");

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("meta", meta);
        root.put("person", Map.of("name", "Your Name"));
        root.put("sections", List.of(
                section("contact", "contact", "CONTACT INFO"),
                section("summary", "text", "SUMMARY"),
                section("experience", "entries", "WORK EXPERIENCE"),
                section("education", "entries", "EDUCATION"),
                section("skills", "categories", "SKILLS"),
                section("languages", "categories", "LANGUAGES")));
        return Tree.freezeMap(root);
    }

    /** A new cover letter (ADR-0026): sender, recipient and body sections, salutation and closing. */
    public static Map<String, Object> coverLetter() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("type", "cover-letter");
        meta.put("lang", "en");
        meta.put("theme", "classic");

        Map<String, Object> letter = new LinkedHashMap<>();
        letter.put("salutation", "Dear Hiring Manager,");
        letter.put("closing", "Sincerely,");

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("meta", meta);
        root.put("person", Map.of("name", "Your Name"));
        root.put("letter", letter);
        root.put("sections", List.of(
                section("contact", "contact", "Sender"),
                section("recipient", "lines", "Recipient"),
                section("body", "text", "Letter")));
        return Tree.freezeMap(root);
    }

    /** The starter for {@code type}. */
    public static Map<String, Object> of(com.pdfgen.domain.model.DocumentType type) {
        return switch (type) {
            case CV -> cv();
            case COVER_LETTER -> coverLetter();
        };
    }

    private static Map<String, Object> section(String id, String kind, String title) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("id", id);
        section.put("kind", kind);
        section.put("title", title);
        section.put("items", List.of());
        return section;
    }
}
