package com.pdfgen.adapters.render;

import com.openhtmltopdf.pdfboxout.PdfBoxRenderer;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.pdfgen.application.PdfRenderer;
import com.pdfgen.domain.model.Document;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;

import com.openhtmltopdf.slf4j.Slf4jLogger;
import com.openhtmltopdf.util.XRLog;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * HTML → PDF with OpenHTMLtoPDF (ADR-0002). The HTML is parsed by jsoup first, so templates do not
 * need to be well-formed XHTML (ADR-0006). Metadata dates are pinned so output is reproducible (ADR-0011).
 */
public final class OpenHtmlPdfRenderer implements PdfRenderer {

    static final String PRODUCER = "pdf-generator";

    static {
        // Route OpenHTMLtoPDF's java.util.logging output through SLF4J / Spring Boot logging config.
        XRLog.setLoggerImpl(new Slf4jLogger());
    }

    private final HtmlRenderer html;
    private final FontRegistry fonts;

    public OpenHtmlPdfRenderer(HtmlRenderer html, FontRegistry fonts) {
        this.html = html;
        this.fonts = fonts;
    }

    @Override
    public byte[] render(Document document) {
        return toPdf(html.render(document), document.person().name());
    }

    byte[] toPdf(String htmlContent, String author) {
        org.w3c.dom.Document dom = new W3CDom().fromJsoup(Jsoup.parse(htmlContent));
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();
        builder.withW3cDocument(dom, "classpath:/");
        builder.withProducer(PRODUCER);

        try (PDDocument pdf = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream();
             Closeable loadedFonts = fonts.registerAll(builder.usePDDocument(pdf), pdf);
             PdfBoxRenderer renderer = builder.buildPdfRenderer()) {
            renderer.layout();
            renderer.createPDFKeepOpen();
            PDDocumentInformation info = pdf.getDocumentInformation();
            info.setAuthor(author);
            info.setCreator(PRODUCER);
            // No dates at all: reproducible, and no misleading timestamp in a document sent to recruiters.
            info.setCreationDate(null);
            info.setModificationDate(null);
            pdf.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("PDF generation failed", e);
        }
    }
}
