package com.pdfgen.application;

import com.pdfgen.domain.DocumentException;

/** The document changed on disk (e.g. edited in VS Code) since the editor loaded it (ADR-0022). */
public class VersionConflictException extends DocumentException {

    public VersionConflictException(String id) {
        super("Document '" + id + "' was changed outside the editor since you opened it. Reload it to continue.");
    }

    protected VersionConflictException(String id, String message) {
        super(message);
    }
}
