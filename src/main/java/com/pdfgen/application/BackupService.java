package com.pdfgen.application;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;

/** Export of all data to one backup file, and restore from it (ADR-0028). */
public final class BackupService {

    private final DataArchive archive;
    private final DocumentService documents;

    public BackupService(DataArchive archive, DocumentService documents) {
        this.archive = archive;
        this.documents = documents;
    }

    public void export(OutputStream out) throws IOException {
        archive.export(out);
    }

    /**
     * Replaces the data with the backup's. Documents of the backup that do not load (e.g. a variant whose parent is
     * missing) are restored anyway and reported as warnings, so nothing in the backup is lost.
     */
    public RestoreReport restore(Path backupZip) {
        DataArchive.Restored restored = archive.restore(backupZip);
        List<String> warnings = documents.list().stream()
                .filter(summary -> summary.loadError().isPresent())
                .map(summary -> summary.id() + ": " + summary.loadError().get())
                .toList();
        return new RestoreReport(restored.restored(), restored.removed(), warnings, restored.safetyBackup());
    }

    public record RestoreReport(List<String> restored, List<String> removed, List<String> warnings,
                                String safetyBackup) {
        public RestoreReport {
            restored = List.copyOf(restored);
            removed = List.copyOf(removed);
            warnings = List.copyOf(warnings);
        }
    }
}
