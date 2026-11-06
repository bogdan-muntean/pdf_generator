package com.pdfgen.adapters.web;

import com.pdfgen.application.DocumentService;
import com.pdfgen.application.DocumentService.EditableDocument;
import com.pdfgen.application.DocumentService.RenderedPdf;
import com.pdfgen.application.PhotoLibrary;
import com.pdfgen.domain.DocumentException;
import com.pdfgen.domain.model.DocumentType;
import com.pdfgen.domain.model.DocumentTypeRules;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Editor API (ADR-0022): load a document for editing, save it with optimistic locking, preview a draft,
 * create documents and variants, delete variants, upload and list photos (ADR-0024). Documents are exchanged whole
 * as JSON trees.
 */
@RestController
@RequestMapping("/api")
public class EditorController {

    private final DocumentService service;
    private final PhotoLibrary photos;
    /** Rendering is CPU-heavy; previews are rendered one at a time. */
    private final Object previewLock = new Object();

    public EditorController(DocumentService service, PhotoLibrary photos) {
        this.service = service;
        this.photos = photos;
    }

    /** {@code rules}: what the document's type allows, so the editor offers only that (ADR-0026). */
    public record EditView(String id, boolean variant, String parentId, Map<String, Object> tree,
                           Map<String, Object> parentTree, String version, boolean writtenByApp,
                           List<String> warnings, Rules rules) {
    }

    /** {@code themes}: the themes available for the document's type (ADR-0027). */
    public record Rules(List<String> sectionKinds, int maxContact, boolean letter, boolean qrCode, boolean photo,
                        boolean jobTitle, List<String> themes) {
        static Rules of(DocumentTypeRules rules, List<String> themes) {
            return new Rules(rules.sectionKinds(), Math.min(rules.maxContact(), 99), rules.letter(), rules.qrCode(),
                    rules.photo(), rules.personKeys().contains("jobTitle"), themes);
        }
    }

    private EditView view(EditableDocument doc) {
        return new EditView(doc.id(), doc.variant(), doc.parentId().orElse(null), doc.tree(),
                doc.parentTree().orElse(null), doc.version(), doc.writtenByApp(), doc.warnings(),
                Rules.of(service.rules(doc), service.themes(doc)));
    }

    public record SaveRequest(Map<String, Object> tree) {
    }

    public record NewVariantRequest(String id) {
    }

    /** {@code type}: {@code cv} or {@code cover-letter}. */
    public record NewDocumentRequest(String id, String type) {
    }

    @GetMapping("/documents/{id}/edit")
    public EditView edit(@PathVariable String id) {
        return view(service.openForEdit(id));
    }

    /** Saves the whole document. {@code If-Match} must carry the version returned by the last load/save. */
    @PutMapping("/documents/{id}")
    public ResponseEntity<EditView> save(@PathVariable String id,
                                         @RequestHeader(HttpHeaders.IF_MATCH) String version,
                                         @RequestBody SaveRequest request) {
        EditView saved = view(service.save(id, request.tree(), unquote(version)));
        return ResponseEntity.ok().eTag(saved.version()).body(saved);
    }

    @PostMapping("/preview")
    public ResponseEntity<byte[]> preview(@RequestBody SaveRequest request) {
        RenderedPdf pdf;
        synchronized (previewLock) {
            pdf = service.preview(request.tree());
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(pdf.content());
    }

    @PostMapping("/documents/{parentId}/variants")
    public ResponseEntity<Map<String, String>> createVariant(@PathVariable String parentId,
                                                             @RequestBody NewVariantRequest request) {
        service.createVariant(parentId, request.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", request.id()));
    }

    /** A new base document from the starter skeleton (ADR-0024). */
    @PostMapping("/documents")
    public ResponseEntity<Map<String, String>> createDocument(@RequestBody NewDocumentRequest request) {
        DocumentType type = DocumentType.fromKey(String.valueOf(request.type())).orElseThrow(() ->
                new DocumentException("Choose the type of the new document: " + Arrays.stream(DocumentType.values())
                        .map(DocumentType::key).toList() + "; found '" + request.type() + "'"));
        service.createDocument(request.id(), type);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", request.id()));
    }

    /** Header with the ids the user confirmed for a cascading delete, comma-separated (ADR-0029). */
    public static final String EXPECTED_HEADER = "X-Pdfgen-Expected";

    /**
     * Deletes a document; with {@code cascade=true} also every variant built on it. Needs
     * {@code X-Pdfgen-Action: delete} and, for a cascade, the confirmed list in {@code X-Pdfgen-Expected}
     * (409 if it is out of date).
     */
    @DeleteMapping("/documents/{id}")
    public ResponseEntity<?> delete(@PathVariable String id,
                                    @RequestParam(defaultValue = "false") boolean cascade,
                                    @RequestHeader(name = AppController.ACTION_HEADER, required = false) String action,
                                    @RequestHeader(name = EXPECTED_HEADER, required = false) String expected) {
        if (!"delete".equals(action)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "missing header " + AppController.ACTION_HEADER + ": delete"));
        }
        if (cascade && (expected == null || expected.isBlank())) {
            throw new DocumentException("A cascading delete needs the confirmed list of documents in " + EXPECTED_HEADER);
        }
        java.util.Optional<List<String>> confirmed = expected == null || expected.isBlank() ? java.util.Optional.empty()
                : java.util.Optional.of(Arrays.stream(expected.split(",")).map(String::strip)
                .filter(s -> !s.isEmpty()).toList());
        return ResponseEntity.ok(service.delete(id, cascade, confirmed));
    }

    @GetMapping("/assets/images")
    public List<String> images() {
        return photos.list();
    }

    /**
     * Upload of a photo as the raw request body. Only {@code image/jpeg} and {@code image/png} are accepted
     * (anything else: 415): unlike multipart forms, these content types cannot be sent cross-site without a
     * CORS preflight, which this server never grants (ADR-0024).
     */
    @PostMapping(path = "/assets/images", consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE})
    public ResponseEntity<Map<String, String>> upload(@RequestParam(defaultValue = "photo") String name,
                                                      HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > PhotoLibrary.MAX_BYTES) {
            throw new PhotoLibrary.ImageTooLargeException();
        }
        // Bounded read: never buffer more than the limit, whatever Content-Length said.
        byte[] content = request.getInputStream().readNBytes(PhotoLibrary.MAX_BYTES + 1);
        if (content.length > PhotoLibrary.MAX_BYTES) {
            throw new PhotoLibrary.ImageTooLargeException();
        }
        String path = photos.upload(name, content);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("path", path));
    }

    /** Thumbnail for the editor. Only images listed by {@link #images()} are served. */
    @GetMapping("/assets/image")
    public ResponseEntity<byte[]> image(@RequestParam String path) {
        return photos.image(path)
                .map(image -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(image.mediaType()))
                        .header("X-Content-Type-Options", "nosniff")
                        .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                        .body(image.content()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** ETags travel quoted ({@code "abc"}); accept both forms. */
    private static String unquote(String version) {
        String v = version.strip();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        return v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"") ? v.substring(1, v.length() - 1) : v;
    }
}
