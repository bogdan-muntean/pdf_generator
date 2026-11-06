package com.pdfgen.domain.model;

import java.util.Arrays;
import java.util.Optional;

/** What a contact line is; the theme picks the icon from it. */
public enum ContactType {
    LOCATION, PHONE, EMAIL, WEBSITE, GITHUB, LINKEDIN, OTHER;

    public String key() {
        return name().toLowerCase();
    }

    public static Optional<ContactType> fromKey(String key) {
        return Arrays.stream(values()).filter(type -> type.key().equals(key)).findFirst();
    }
}
