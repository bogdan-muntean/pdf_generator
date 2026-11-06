package com.pdfgen.application;

import com.pdfgen.domain.DocumentException;

/** A source file or asset exists but cannot be read or parsed. */
public class DocumentLoadException extends DocumentException {

    public DocumentLoadException(String message) {
        super(message);
    }

    public DocumentLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
