package com.pdfgen.domain.overlay;

import java.util.List;
import java.util.Map;

/**
 * A patch over its parent document: a base, or another variant (variant chains, ADR-0025).
 *
 * @param id         the variant's own document id (its file name)
 * @param baseId     id of the parent document it extends ({@code extends:}); a base or another variant
 * @param meta       optional header overrides of {@code meta} (theme, lang, outputName)
 * @param operations ordered operations
 */
public record Variant(String id, String baseId, Map<String, Object> meta, List<Operation> operations) {

    public Variant {
        meta = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(meta));
        operations = List.copyOf(operations);
    }
}
