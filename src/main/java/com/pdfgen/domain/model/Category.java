package com.pdfgen.domain.model;

import java.util.List;

/** A labelled list of values, e.g. {@code Back-end: PHP; Node.js}. */
public record Category(String id, String label, List<String> values) {

    public Category {
        values = List.copyOf(values);
    }
}
