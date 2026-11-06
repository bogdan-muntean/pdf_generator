package com.pdfgen.application;

import com.pdfgen.domain.DocumentException;

public class DocumentNotFoundException extends DocumentException {

    public DocumentNotFoundException(String id) {
        super("Document '" + id + "' not found");
    }
}
