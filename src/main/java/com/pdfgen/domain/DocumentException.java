package com.pdfgen.domain;

/**
 * Base type for every expected, user-facing problem with a document
 * (invalid data, broken variant, missing file). Messages are meant to be shown to the author.
 */
public class DocumentException extends RuntimeException {

    public DocumentException(String message) {
        super(message);
    }

    public DocumentException(String message, Throwable cause) {
        super(message, cause);
    }
}
