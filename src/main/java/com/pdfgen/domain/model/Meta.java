package com.pdfgen.domain.model;

import java.util.Optional;

/**
 * Document-level settings.
 *
 * @param type       what kind of document this is
 * @param lang       BCP-47 language tag, e.g. {@code en}, {@code ro}
 * @param theme      name of the presentation theme
 * @param outputName file name of the generated PDF (without extension); defaults to the document id
 */
public record Meta(DocumentType type, String lang, String theme, Optional<String> outputName) {
}
