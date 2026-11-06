package com.pdfgen.domain.model;

import java.util.List;
import java.util.Set;

/**
 * Everything that differs between document types, in one place (ADR-0026). Used by the mapper to validate and
 * sent to the editor, so the UI offers only what the type's theme can render.
 *
 * @param sectionKinds   section kinds allowed, in the order the editor offers them
 * @param personKeys     keys allowed in {@code person}
 * @param maxContact     how many {@code contact} sections are allowed
 * @param letter         whether the {@code letter} root is required (true) or rejected (false)
 * @param qrCode         whether a contact section may carry a QR code
 * @param photo          whether {@code person.photo} is allowed
 */
public record DocumentTypeRules(List<String> sectionKinds, Set<String> personKeys, int maxContact, boolean letter,
                                boolean qrCode, boolean photo) {

    public static final String CONTACT = "contact";

    private static final DocumentTypeRules CV = new DocumentTypeRules(
            List.of(CONTACT, "text", "entries", "categories", "list"),
            Set.of("name", "jobTitle", "photo"), Integer.MAX_VALUE, false, true, true);

    private static final DocumentTypeRules COVER_LETTER = new DocumentTypeRules(
            List.of(CONTACT, "lines", "text"),
            Set.of("name"), 1, true, false, false);

    public DocumentTypeRules {
        sectionKinds = List.copyOf(sectionKinds);
        personKeys = Set.copyOf(personKeys);
    }

    public static DocumentTypeRules of(DocumentType type) {
        return switch (type) {
            case CV -> CV;
            case COVER_LETTER -> COVER_LETTER;
        };
    }
}
