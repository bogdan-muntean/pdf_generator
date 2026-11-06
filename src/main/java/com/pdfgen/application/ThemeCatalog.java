package com.pdfgen.application;

import com.pdfgen.domain.model.DocumentType;

import java.util.List;

/** Port: the themes available per document type (ADR-0027), e.g. {@code cover-letter -> [classic, modern]}. */
public interface ThemeCatalog {

    /** Theme names for {@code type}, sorted; empty if there are none. */
    List<String> themes(DocumentType type);
}
