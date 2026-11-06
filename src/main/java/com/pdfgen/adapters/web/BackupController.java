package com.pdfgen.adapters.web;

import com.pdfgen.application.BackupService;
import com.pdfgen.application.BackupService.RestoreReport;
import com.pdfgen.application.DataArchive.InvalidBackupException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Backup and restore of all documents and photos (ADR-0028). Both need {@code X-Pdfgen-Action}; the custom
 * header means a page from another site cannot trigger them (it would need a CORS preflight this app never
 * approves), and a plain link cannot download the data either.
 */
@RestController
@RequestMapping("/api/backup")
public class BackupController {

    /** Upper limit for an uploaded backup file. */
    static final long MAX_UPLOAD_BYTES = 100L * 1024 * 1024;
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");

    private final BackupService backups;

    public BackupController(BackupService backups) {
        this.backups = backups;
    }

    @GetMapping
    public ResponseEntity<?> export(@RequestHeader(name = AppController.ACTION_HEADER, required = false) String action)
            throws IOException {
        if (!"backup".equals(action)) {
            return forbidden("backup");
        }
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        backups.export(zip);
        String name = "pdf-generator-backup-" + LocalDateTime.now().format(FILE_STAMP) + ".zip";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(zip.toByteArray());
    }

    /** The backup file as the raw body ({@code application/zip}); replaces all documents and photos. */
    @PostMapping(path = "/restore", consumes = "application/zip")
    public ResponseEntity<?> restore(@RequestHeader(name = AppController.ACTION_HEADER, required = false) String action,
                                     HttpServletRequest request) throws IOException {
        if (!"restore".equals(action)) {
            return forbidden("restore");
        }
        Path upload = Files.createTempFile("pdfgen-restore-", ".zip");
        try {
            try (InputStream in = request.getInputStream(); OutputStream out = Files.newOutputStream(upload)) {
                long copied = 0;
                byte[] buffer = new byte[64 * 1024];
                for (int n; (n = in.read(buffer)) > 0; ) {
                    copied += n;
                    if (copied > MAX_UPLOAD_BYTES) {
                        throw new InvalidBackupException("the file is larger than "
                                + MAX_UPLOAD_BYTES / (1024 * 1024) + " MB");
                    }
                    out.write(buffer, 0, n);
                }
            }
            RestoreReport report = backups.restore(upload);
            return ResponseEntity.ok(report);
        } finally {
            Files.deleteIfExists(upload);
        }
    }

    private static ResponseEntity<Map<String, String>> forbidden(String action) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "missing header " + AppController.ACTION_HEADER + ": " + action));
    }
}
