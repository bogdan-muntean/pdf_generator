package com.pdfgen.application;

import com.pdfgen.domain.model.Document;

/** Port: turns a resolved document into PDF bytes using the document's theme (ADR-0002). */
public interface PdfRenderer {

    byte[] render(Document document);
}
