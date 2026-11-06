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
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * samples/*.yaml → DocumentService → real PDF, checked with PDFBox (ADR-0011).
 * Uses bundled fonts only, so the result does not depend on the machine.
 */
class EndToEndPdfTest {

    private static DocumentService service;
    private static RenderedPdf base;
    private static RenderedPdf variant;

    @BeforeAll
    static void render() {
        var repository = new FileDocumentRepository(Path.of("samples"));
        var renderer = new OpenHtmlPdfRenderer(new HtmlRenderer(repository), new FontRegistry(Optional.empty()));
        service = new DocumentService(repository, renderer, new ClasspathThemeCatalog());
        base = service.render("cv-sample");
        variant = service.render("cv-sample-qa");
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static String pageText(byte[] pdf, int page) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            return stripper.getText(document);
        }
    }

    @Test
    void baseRendersAsMultiPageA4WithFileNameFromMeta() throws IOException {
        assertThat(base.fileName()).isEqualTo("CV_Alex_Popescu.pdf");
        try (PDDocument document = Loader.loadPDF(base.content())) {
            assertThat(document.getNumberOfPages()).isGreaterThanOrEqualTo(2);
            PDPage first = document.getPage(0);
            assertThat(first.getMediaBox().getWidth()).isCloseTo(595f, org.assertj.core.data.Offset.offset(1f));
            assertThat(document.getDocumentInformation().getAuthor()).isEqualTo("Alex Popescu");
            assertThat(document.getDocumentInformation().getCreationDate()).isNull();
        }
    }

    @Test
    void sectionTitleRepeatsOnTheNextPage() throws IOException {
        String secondPage = pageText(base.content(), 2);
        assertThat(secondPage.lines().filter(line -> !line.isBlank()).skip(1).findFirst())
                .as("first line after the page number on page 2")
                .hasValue("PROJECTS");
    }

    @Test
    void pageNumbersArePrinted() throws IOException {
        assertThat(pageText(base.content(), 2).lines().map(String::strip)).contains("2");
    }

    @Test
    void textExtractsWithoutLigatureArtifactsAndInReadingOrder() throws IOException {
        String text = text(base.content());
        assertThat(text).contains("Optimized PostgreSQL queries", "Acme Software", "automation")
                .doesNotContain("Ɵ", "Ō", "ƞ");
        assertThat(text.indexOf("Acme Software – Backend Developer"))
                .isLessThan(text.indexOf("Designed and maintained REST APIs"))
                .isLessThan(text.indexOf("Globex Corporation"));
    }

    /** Calibri is where the "ti"/"ft" ligature bug showed up (ADR-0019); runs only where Calibri exists. */
    @Test
    @EnabledIf("calibriInstalled")
    void calibriTextExtractsWithoutLigatureArtifacts() throws IOException {
        var repository = new FileDocumentRepository(Path.of("samples"));
        var renderer = new OpenHtmlPdfRenderer(new HtmlRenderer(repository), new FontRegistry(Optional.of(WINDOWS_FONTS)));
        String text = text(new DocumentService(repository, renderer, new ClasspathThemeCatalog()).render("cv-sample").content());
        assertThat(text).contains("Optimized PostgreSQL queries", "Acme Software").doesNotContain("Ɵ", "Ō", "ƞ");
    }

    private static final Path WINDOWS_FONTS = Path.of("C:/Windows/Fonts");

    static boolean calibriInstalled() {
        return java.nio.file.Files.isRegularFile(WINDOWS_FONTS.resolve("calibri.ttf"));
    }

    @Test
    void variantChangesAreVisibleInThePdf() throws IOException {
        String text = text(variant.content());
        assertThat(variant.fileName()).isEqualTo("CV_Alex_Popescu_QA.pdf");
        assertThat(text).contains("QA AUTOMATION ENGINEER", "PROFESSIONAL EXPERIENCE",
                        "Designed API test suites", "Owned the regression test plan", "Testing: JUnit 5")
                .doesNotContain("WORK EXPERIENCE", "Maintained the CI pipeline", "Stock Portfolio Analytics");
        assertThat(text.indexOf("CORE TECHNOLOGIES")).isLessThan(text.indexOf("PROFESSIONAL EXPERIENCE"));
        assertThat(variant.warnings()).singleElement().asString().contains("soft-skills");
    }

    @Test
    void everyFontIsEmbedded() throws IOException {
        try (PDDocument document = Loader.loadPDF(base.content())) {
            List<String> notEmbedded = new ArrayList<>();
            for (PDPage page : document.getPages()) {
                for (var name : page.getResources().getFontNames()) {
                    PDFont font = page.getResources().getFont(name);
                    if (!font.isEmbedded()) {
                        notEmbedded.add(font.getName());
                    }
                }
            }
            assertThat(notEmbedded).isEmpty();
        }
    }

    @Test
    void romanianDiacriticsSurvive() throws IOException {
        String text = text(service.render("cv-sample-ro").content());
        assertThat(text).contains("ă â î ș ț Ă Â Î Ș Ț", "Făgăraș", "experiență");
    }

    @Test
    void renderingIsDeterministic() throws IOException {
        assertThat(text(service.render("cv-sample").content())).isEqualTo(text(base.content()));
    }
}
