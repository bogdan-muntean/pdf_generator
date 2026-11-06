package com.pdfgen.domain.model;

import java.util.Optional;

/**
 * The document owner, shown in the header.
 *
 * @param photo path of the photo relative to the data directory, e.g. {@code assets/photo.jpg}
 */
public record Person(String name, Optional<String> jobTitle, Optional<String> photo) {
}
