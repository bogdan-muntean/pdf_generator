package com.pdfgen.application;

import java.util.List;

/** The documents a delete would remove are not the ones the user confirmed (ADR-0029): nothing was deleted. */
public class StaleDeleteException extends VersionConflictException {

    private final List<String> actual;

    public StaleDeleteException(String id, List<String> actual) {
        super(id, "The documents that deleting '" + id + "' removes changed since you confirmed: now "
                + String.join(", ", actual) + ". Nothing was deleted; check the list and confirm again.");
        this.actual = List.copyOf(actual);
    }

    public List<String> actual() {
        return actual;
    }
}
