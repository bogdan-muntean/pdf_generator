package com.pdfgen.application;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;

/** Port: backup of the whole data folder as one file, and restore from it (ADR-0028). */
public interface DataArchive {

    /** Writes every document and asset (never {@code .history}) as a backup ZIP. */
    void export(OutputStream out) throws IOException;

    /**
     * Replaces all documents and assets with the backup's, after validating it completely. A safety backup of the
     * current data is written first; on any failure the current data is left as it was.
     *
     * @throws InvalidBackupException if the file is not a valid backup (nothing is changed)
     */
    Restored restore(Path backupZip);

    /** {@code restored}/{@code removed}: document ids; {@code safetyBackup}: path of the ZIP of the data before. */
    record Restored(List<String> restored, List<String> removed, String safetyBackup) {
        public Restored {
            restored = List.copyOf(restored);
            removed = List.copyOf(removed);
        }
    }

    /** The uploaded file is not a backup this app can restore. */
    class InvalidBackupException extends com.pdfgen.domain.DocumentException {
        public InvalidBackupException(String message) {
            super("Not a valid backup: " + message);
        }
    }
}
