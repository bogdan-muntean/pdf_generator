package com.pdfgen.adapters.cli;

import com.pdfgen.adapters.render.ClasspathThemeCatalog;
import com.pdfgen.adapters.render.FontRegistry;
import com.pdfgen.adapters.render.HtmlRenderer;
import com.pdfgen.adapters.render.OpenHtmlPdfRenderer;
import com.pdfgen.adapters.storage.FileDocumentRepository;
import com.pdfgen.application.DocumentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CliCommandsTest {

    @TempDir
    Path output;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private int run(String... args) {
        var repository = new FileDocumentRepository(Path.of("samples"));
        var service = new DocumentService(repository,
                new OpenHtmlPdfRenderer(new HtmlRenderer(repository), new FontRegistry(Optional.empty())),
                new ClasspathThemeCatalog());
        return new CliCommands(service, output).execute(args, new PrintWriter(out, true), new PrintWriter(err, true));
    }

    @Test
    void listsDocuments() {
        assertThat(run("list")).isZero();
        assertThat(out.toString()).contains("cv-sample-qa").contains("variant of cv-sample");
    }

    @Test
    void rendersAVariantToTheOutputDirectory() {
        assertThat(run("render", "cv-sample-qa", "--pdfgen.data-dir=ignored-by-picocli")).isZero();
        assertThat(output.resolve("CV_Alex_Popescu_QA.pdf")).exists().isNotEmptyFile();
        assertThat(err.toString()).contains("WARN");
    }

    @Test
    void failsWithNonZeroExitCodeButRendersTheRest() {
        assertThat(run("render", "missing", "cv-sample")).isEqualTo(1);
        assertThat(err.toString()).contains("FAIL  missing: Document 'missing' not found");
        assertThat(output.resolve("CV_Alex_Popescu.pdf")).exists();
    }

    @Test
    void requiresSomethingToRender() {
        assertThat(run("render")).isEqualTo(2);
    }
}
