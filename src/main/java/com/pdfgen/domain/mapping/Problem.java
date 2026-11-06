package com.pdfgen.domain.mapping;

/**
 * One validation problem.
 *
 * @param path id-based field path, e.g. {@code sections[experience].items[acme].heading}
 *             (index instead of id for elements without one); empty for the document root.
 *             The editor uses it to highlight the field.
 */
public record Problem(String path, String message) {

    @Override
    public String toString() {
        return path.isEmpty() ? message : path + ": " + message;
    }
}
