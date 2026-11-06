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

/** The "modern" cover letter theme (ADR-0027), switched on by a variant that sets only meta.theme. */
class ModernLetterEndToEndTest {

    private static final int DARK_BLUE = 0x1F4E79;
    private static final int LINK_BLUE = 0x1155CC;

    private static RenderedPdf letter;

    @BeforeAll
    static void render() {
        var repository = new FileDocumentRepository(Path.of("samples"));
        var themes = new ClasspathThemeCatalog();
        var renderer = new OpenHtmlPdfRenderer(new HtmlRenderer(repository, themes), new FontRegistry(Optional.empty()));
        letter = new DocumentService(repository, renderer, themes).render("cover-letter-sample-modern");
    }

    private record Run(String font, int rgb) {
    }

    /** Line text -> font and colour of its first character. */
    private static Map<String, Run> runs(byte[] pdf) throws IOException {
        Map<String, Run> runs = new LinkedHashMap<>();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper() {
                // Text is written per line after the whole page was processed: remember each glyph's colour.
                private final Map<TextPosition, Integer> colours = new java.util.IdentityHashMap<>();

                {   // the text stripper ignores colour operators unless they are registered
                    addOperator(new org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorSpace(this));
                    addOperator(new org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColor(this));
                    addOperator(new org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorN(this));
                    addOperator(new org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor(this));
                    addOperator(new org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor(this));
                }

                @Override
                protected void processTextPosition(TextPosition text) {
                    try {
                        colours.put(text, getGraphicsState().getNonStrokingColor().toRGB());
                    } catch (IOException e) {
                        colours.put(text, -1);
                    }
                    super.processTextPosition(text);
                }

                @Override
                protected void writeString(String text, List<TextPosition> positions) throws IOException {
                    TextPosition first = positions.getFirst();
                    runs.putIfAbsent(text.strip(), new Run(first.getFont().getName(), colours.getOrDefault(first, -1)));
                    super.writeString(text, positions);
                }
            };
            stripper.getText(document);
        }
        return runs;
    }

    @Test
    void isOnePageInReadingOrderWithTheNameInCapitals() throws IOException {
        assertThat(letter.fileName()).isEqualTo("Alex_Popescu_cover_letter_Initech_modern.pdf");
        String text;
        try (PDDocument document = Loader.loadPDF(letter.content())) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            text = new PDFTextStripper().getText(document);
        }
        List<String> lines = text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        assertThat(lines.subList(0, 3)).containsExactly("ALEX POPESCU",
                "Cluj-Napoca, Romania  |  alex.popescu@example.com  |  +40 700 000 000",
                "GitHub  |  LinkedIn  |  My Website");
        List<String> order = List.of("6 November 2026", "Initech Romania", "Application for the Java Developer position",
                "Dear Hiring Manager,", "Sincerely,", "Alex Popescu");
        int last = 2;
        for (String expected : order) {
            int at = lines.subList(last + 1, lines.size()).indexOf(expected);
            assertThat(at).as(expected).isNotNegative();
            last += at + 1;
        }
        assertThat(text).doesNotContain("Sender").doesNotContain("Recipient");
    }

    @Test
    void usesTheReferenceColoursAndWeights() throws IOException {
        Map<String, Run> runs = runs(letter.content());
        assertThat(runs.get("ALEX POPESCU").font()).contains("LiberationSans-Bold");
        assertThat(runs.get("ALEX POPESCU").rgb()).isEqualTo(DARK_BLUE);
        assertThat(runs.get("Initech Romania").font()).contains("Bold");
        assertThat(runs.get("Initech Romania").rgb()).isEqualTo(DARK_BLUE);
        assertThat(runs.get("Dear Hiring Manager,").font()).contains("LiberationSans").doesNotContain("Bold");
        assertThat(runs.get("Sincerely,").font()).doesNotContain("Bold");
        // the links line is extracted as one string; its first glyph belongs to the GitHub link
        assertThat(runs.get("GitHub  |  LinkedIn  |  My Website").rgb()).isEqualTo(LINK_BLUE);
    }

    @Test
    void keepsTheLinks() throws IOException {
        List<String> uris = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(letter.content())) {
            for (PDAnnotation annotation : document.getPage(0).getAnnotations()) {
                if (annotation instanceof PDAnnotationLink link && link.getAction() instanceof PDActionURI uri) {
                    uris.add(uri.getURI());
                }
            }
        }
        assertThat(uris).contains("mailto:alex.popescu@example.com", "https://github.com/alex-popescu",
                "https://alex-popescu.example.com/");
    }
}
