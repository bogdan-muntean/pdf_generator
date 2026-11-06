package com.pdfgen.domain.overlay;

import java.util.List;
import java.util.Map;

/** The merged raw tree plus non-fatal remarks (e.g. ids not mentioned by an {@code order}). */
public record OverlayResult(Map<String, Object> document, List<String> warnings) {

    public OverlayResult {
        warnings = List.copyOf(warnings);
    }
}
