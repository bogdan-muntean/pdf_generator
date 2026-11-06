package com.pdfgen.domain.model;

import java.util.List;
import java.util.Optional;

/**
 * A fully resolved, validated document ready to be rendered (ADR-0008). {@code letter} is present exactly for
 * cover letters (ADR-0026).
 */
public record Document(Meta meta, Person person, List<Section> sections, Optional<Letter> letter) {

    public Document {
        sections = List.copyOf(sections);
    }

    public Document(Meta meta, Person person, List<Section> sections) {
        this(meta, person, sections, Optional.empty());
    }

    /** The name signed under a letter: {@code letter.signature}, otherwise the person's name. */
    public String signature() {
        return letter.flatMap(Letter::signature).orElse(person.name());
    }
}
