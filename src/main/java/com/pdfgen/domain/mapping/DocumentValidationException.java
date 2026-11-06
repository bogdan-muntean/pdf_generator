package com.pdfgen.domain.mapping;

import com.pdfgen.domain.DocumentException;

import java.util.List;

/** The (merged) document tree is not a valid document. Lists every problem found, not just the first. */
public class DocumentValidationException extends DocumentException {

    private final List<Problem> details;

    public DocumentValidationException(String source, List<Problem> details) {
        super("Invalid document " + source + ":\n  - "
                + String.join("\n  - ", details.stream().map(Problem::toString).toList()));
        this.details = List.copyOf(details);
    }

    /** Problems as {@code "path: message"} texts. */
    public List<String> problems() {
        return details.stream().map(Problem::toString).toList();
    }

    /** Problems with separate path and message (for the editor). */
    public List<Problem> details() {
        return details;
    }
}
