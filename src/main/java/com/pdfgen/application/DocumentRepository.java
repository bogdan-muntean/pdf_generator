package com.pdfgen.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Port: where document sources (base documents and variants) come from and are saved to (ADR-0007, ADR-0022). */
public interface DocumentRepository {

    /** Ids of all available documents, sorted. */
    List<String> listIds();

    boolean exists(String id);

    /**
     * The immutable raw tree of a document (see {@code com.pdfgen.domain.tree.Tree}).
     *
     * @throws DocumentNotFoundException if no such document exists
     * @throws DocumentLoadException     if the source cannot be read or parsed
     */
    Map<String, Object> loadTree(String id);

    /** A token that changes whenever the stored document changes (content hash), for optimistic locking. */
    String fingerprint(String id);

    /** False if the file was written by hand: saving from the app will drop its comments. */
    boolean writtenByApp(String id);

    /** Writes the document, keeping a backup of the previous version. */
    void save(String id, Map<String, Object> tree);

    /** Deletes the document, keeping a backup. */
    void delete(String id);

    /**
     * Deletes all the documents, in the given order, all or nothing (ADR-0029): a ZIP of them is written to
     * {@code .history} first and, if a delete fails, the files already deleted are put back.
     *
     * @return the path of that ZIP, relative to the data folder
     */
    String deleteAll(List<String> ids, String label);

    /** Latest modification time of any document source or asset; used for live preview refresh. */
    Instant lastModified();
}
