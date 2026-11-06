package com.pdfgen.domain.model;

import java.util.Optional;

/**
 * The parts of a cover letter that are not sections (ADR-0026): the {@code letter} root of the data file.
 *
 * @param signature name under the closing; {@link Document#signature()} falls back to the person's name
 * @param date      free text written by the user (never "today": rendering stays deterministic)
 */
public record Letter(String salutation, String closing, Optional<String> signature, Optional<String> date,
                     Optional<String> subject) {
}
