package com.pdfgen.domain.model;

import java.util.Optional;

/**
 * A QR code pointing to {@code url}, with an optional caption shown next to it.
 * The caption may contain {@code {url}}, which the theme replaces with the emphasized link.
 */
public record QrCode(String url, Optional<String> caption) {
}
