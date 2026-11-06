package com.pdfgen.domain.model;

import java.util.Optional;

/**
 * A paragraph, bullet or list entry. In data files it is either plain text or {@code {id, text}};
 * only items with an id can be targeted by a variant (ADR-0014).
 */
public record TextItem(Optional<String> id, String text) {
}
