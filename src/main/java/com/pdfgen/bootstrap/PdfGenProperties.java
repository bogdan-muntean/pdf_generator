package com.pdfgen.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Settings under {@code pdfgen.*} (see {@code application.yml}); all overridable from the command line,
 * e.g. {@code --pdfgen.data-dir=D:/cv-data}.
 *
 * @param dataDir       folder with document YAML files and assets
 * @param outputDir     where the CLI writes PDFs
 * @param systemFontDir folder with Calibri/Arial; blank = bundled fonts only
 */
@ConfigurationProperties(prefix = "pdfgen")
public record PdfGenProperties(Path dataDir, Path outputDir, String systemFontDir) {

    public PdfGenProperties {
        dataDir = dataDir == null ? Path.of("data") : dataDir;
        outputDir = outputDir == null ? Path.of("output") : outputDir;
        systemFontDir = systemFontDir == null ? "" : systemFontDir;
    }
}
