package com.pdfgen.domain.model;

import java.util.Optional;

/**
 * One contact line, e.g. phone or GitHub.
 *
 * @param label  optional prefix shown before the value, e.g. {@code "Email:"}
 * @param href   optional link target; for email/phone/website a sensible default is derived by the theme
 * @param column layout hint for the two-column contact grid
 */
public record ContactItem(String id, ContactType type, Optional<String> label, String value,
                          Optional<String> href, boolean emphasized, Column column) {

    public enum Column { LEFT, RIGHT }
}
