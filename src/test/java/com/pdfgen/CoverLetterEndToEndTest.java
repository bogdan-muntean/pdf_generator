package com.pdfgen;

import com.pdfgen.adapters.render.ClasspathThemeCatalog;
import com.pdfgen.adapters.render.FontRegistry;
import com.pdfgen.adapters.render.HtmlRenderer;
import com.pdfgen.adapters.render.OpenHtmlPdfRenderer;
import com.pdfgen.adapters.storage.FileDocumentRepository;
import com.pdfgen.application.DocumentService;
import com.pdfgen.application.DocumentService.RenderedPdf;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * samples/cover-letter-sample*.yaml → real PDF (ADR-0026), bundled fonts only (Liberation Serif), and the
 * third-level CV sample (ADR-0025).
 */
class CoverLetterEndToEndTest {

    private static RenderedPdf letter;
    private static RenderedPdf thirdLevelCv;

    @BeforeAll
    static void render() {
        var repository = new FileDocumentRepository(Path.of("samples"));
        var renderer = new OpenHtmlPdfRenderer(new HtmlRenderer(repository), new FontRegistry(Optional.empty()));
        DocumentService service = new DocumentService(repository, renderer, new ClasspathThemeCatalog());
        letter = service.render("cover-letter-sample-initech");
        thirdLevelCv = service.render("cv-sample-qa-acme");
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    /** Line text -> font name of its first character. */
    private static Map<String, String> fontPerLine(byte[] pdf) throws IOException {
        Map<String, String> fonts = new LinkedHashMap<>();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) throws IOException {
                    fonts.putIfAbsent(text.strip(), positions.getFirst().getFont().getName());
                    super.writeString(text, positions);
                }
            };
            stripper.getText(document);
        }
        return fonts;
    }

    @Test
    void isOnePageWithTheFileNameFromMeta() throws IOException {
        assertThat(letter.fileName()).isEqualTo("Alex_Popescu_cover_letter_Initech.pdf");
        try (PDDocument document = Loader.loadPDF(letter.content())) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void textComesOutInReadingOrder() throws IOException {
        List<String> lines = text(letter.content()).lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        assertThat(lines.subList(0, 4)).containsExactly("Alex Popescu", "Cluj-Napoca, Romania",
                "alex.popescu@example.com | +40 700 000 000", "GitHub | LinkedIn | My Website");
        List<String> order = List.of("6 November 2026", "Initech Romania", "21 Example Street", "Cluj-Napoca",
                "Application for the Java Developer position", "Dear Hiring Manager,", "Sincerely,");
        int last = -1;
        for (String expected : order) {
            int at = lines.indexOf(expected);
            assertThat(at).as(expected).isGreaterThan(last);
            last = at;
        }
        assertThat(lines.getLast()).isEqualTo("Alex Popescu"); // the signature falls back to the person's name
        assertThat(String.join(" ", lines)).contains("Java Developer position at Initech")
                .doesNotContain("[Company Name]").doesNotContain("Recipient").doesNotContain("Sender");
    }

    @Test
    void usesTheSerifFontAndBoldWhereTheOriginalDoes() throws IOException {
        Map<String, String> fonts = fontPerLine(letter.content());
        for (String bold : List.of("Alex Popescu", "Initech Romania", "Dear Hiring Manager,", "Sincerely,")) {
            assertThat(fonts.get(bold)).as(bold).contains("LiberationSerif-Bold");
        }
        assertThat(fonts.get("Cluj-Napoca, Romania")).contains("LiberationSerif").doesNotContain("Bold");
    }

    @Test
    void hasMailtoAndProfileLinks() throws IOException {
        List<String> uris = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(letter.content())) {
            for (PDAnnotation annotation : document.getPage(0).getAnnotations()) {
                if (annotation instanceof PDAnnotationLink link && link.getAction() instanceof PDActionURI uri) {
                    uris.add(uri.getURI());
                }
            }
        }
        assertThat(uris).contains("mailto:alex.popescu@example.com", "https://github.com/alex-popescu",
                "https://www.linkedin.com/in/alex-popescu", "https://alex-popescu.example.com/");
    }

    @Test
    void theThirdLevelCvRendersWithChangesFromEveryLevel() throws IOException {
        assertThat(thirdLevelCv.fileName()).isEqualTo("CV_Alex_Popescu_QA_Acme.pdf");
        String text = text(thirdLevelCv.content());
        assertThat(text).contains("QA ENGINEER FOR ACME PAYMENTS") // level 3
                .contains("WHY ACME")                              // level 3
                .contains("PROFESSIONAL EXPERIENCE")               // level 2
                .contains("Testing at Acme")                       // level 3 on an element added by level 2
                .contains("Alex Popescu");                         // base
    }
}
