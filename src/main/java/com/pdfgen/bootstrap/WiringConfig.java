package com.pdfgen.bootstrap;

import com.pdfgen.adapters.render.ClasspathThemeCatalog;
import com.pdfgen.adapters.render.FontRegistry;
import com.pdfgen.adapters.render.HtmlRenderer;
import com.pdfgen.adapters.render.OpenHtmlPdfRenderer;
import com.pdfgen.adapters.storage.FileDocumentRepository;
import com.pdfgen.application.DocumentService;
import com.pdfgen.application.BackupService;
import com.pdfgen.application.PhotoLibrary;
import com.pdfgen.adapters.storage.ZipDataArchive;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The only place where ports meet adapters. Domain and application classes are plain Java
 * and know nothing about Spring (enforced by {@code ArchitectureTest}).
 */
@Configuration
@EnableConfigurationProperties(PdfGenProperties.class)
public class WiringConfig {

    @Bean
    FileDocumentRepository fileDocumentRepository(PdfGenProperties properties) {
        return new FileDocumentRepository(properties.dataDir());
    }

    @Bean
    ClasspathThemeCatalog themeCatalog() {
        return new ClasspathThemeCatalog();
    }

    @Bean
    OpenHtmlPdfRenderer pdfRenderer(FileDocumentRepository repository, PdfGenProperties properties,
                                    ClasspathThemeCatalog themes) {
        Optional<Path> fontDir = Optional.of(properties.systemFontDir()).filter(dir -> !dir.isBlank()).map(Path::of);
        return new OpenHtmlPdfRenderer(new HtmlRenderer(repository, themes), new FontRegistry(fontDir));
    }

    @Bean
    DocumentService documentService(FileDocumentRepository repository, OpenHtmlPdfRenderer renderer,
                                    ClasspathThemeCatalog themes) {
        return new DocumentService(repository, renderer, themes);
    }

    @Bean
    BackupService backupService(FileDocumentRepository repository, DocumentService documents) {
        return new BackupService(new ZipDataArchive(repository.root()), documents);
    }

    @Bean
    PhotoLibrary photoLibrary(FileDocumentRepository repository) {
        return new PhotoLibrary(repository);
    }
}
