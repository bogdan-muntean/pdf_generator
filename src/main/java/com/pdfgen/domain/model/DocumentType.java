package com.pdfgen.domain.model;

import java.util.Arrays;
import java.util.Optional;

/** What a document is. The key is used in data files ({@code meta.type}) and as the theme folder name. */
public enum DocumentType {
    CV("cv"),
    COVER_LETTER("cover-letter");

    private final String key;

    DocumentType(String key) {
        this.key = key;
    }

    /** The value used in data files and as the theme folder name. */
    public String key() {
        return key;
    }

    public static Optional<DocumentType> fromKey(String key) {
        return Arrays.stream(values()).filter(type -> type.key.equals(key)).findFirst();
    }
}
