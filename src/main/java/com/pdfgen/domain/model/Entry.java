package com.pdfgen.domain.model;

import java.util.List;
import java.util.Optional;

/**
 * A job, project or study entry.
 *
 * @param heading    bold first line, e.g. {@code "Acme – Software Developer"}
 * @param subheading optional bold second line, e.g. the institution
 * @param dates      e.g. {@code "Dec 2023 – Aug 2025"}
 */
public record Entry(String id, String heading, Optional<String> subheading, Optional<String> dates,
                    List<TextItem> bullets) {

    public Entry {
        bullets = List.copyOf(bullets);
    }
}
