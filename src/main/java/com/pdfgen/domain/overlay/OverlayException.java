package com.pdfgen.domain.overlay;

import com.pdfgen.domain.DocumentException;

/** A variant cannot be parsed or applied to its base. The message names the variant and the operation. */
public class OverlayException extends DocumentException {

    public OverlayException(String variantId, String where, String problem) {
        super("Variant '" + variantId + "', " + where + ": " + problem);
    }
}
