package com.pdfgen.adapters.web;

import com.pdfgen.application.DocumentRepository;
import com.pdfgen.application.DocumentService;
import com.pdfgen.application.DocumentService.RenderedPdf;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** JSON + PDF endpoints used by the viewer page ({@code static/index.html}). */
@RestController
@RequestMapping("/api")
public class DocumentController {

    private final DocumentService service;
    private final DocumentRepository repository;

    public DocumentController(DocumentService service, DocumentRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    /** {@code type}: {@code cv}, {@code cover-letter}, or null when the document cannot be read. */
    public record DocumentView(String id, boolean variant, String parentId, String type, String loadError) {
    }

    @GetMapping("/documents")
    public List<DocumentView> list() {
        return service.list().stream()
                .map(doc -> new DocumentView(doc.id(), doc.variant(), doc.parentId().orElse(null),
                        doc.type().map(com.pdfgen.domain.model.DocumentType::key).orElse(null),
                        doc.loadError().orElse(null)))
                .toList();
    }

    /** Renders a document. Warnings are returned URL-encoded in the {@code X-Pdfgen-Warnings} header. */
    @GetMapping("/documents/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String id,
                                      @RequestParam(defaultValue = "false") boolean download) {
        RenderedPdf pdf = service.render(id);
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(pdf.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Pdfgen-Warnings", java.net.URLEncoder.encode(
                        String.join("\n", pdf.warnings()), StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(pdf.content());
    }

    /** A token that changes whenever a file in the data directory changes; polled for live preview. */
    @GetMapping("/changes")
    public Map<String, Long> changes() {
        return Map.of("version", repository.lastModified().toEpochMilli());
    }
}
