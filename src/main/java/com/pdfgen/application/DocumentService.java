package com.pdfgen.application;

import com.pdfgen.domain.DocumentException;
import com.pdfgen.domain.mapping.DocumentMapper;
import com.pdfgen.domain.mapping.DocumentValidationException;
import com.pdfgen.domain.mapping.Problem;
import com.pdfgen.domain.model.Document;
import com.pdfgen.domain.model.DocumentType;
import com.pdfgen.domain.model.DocumentTypeRules;
import com.pdfgen.domain.overlay.Operation;
import com.pdfgen.domain.overlay.OverlayDiff;
import com.pdfgen.domain.overlay.OverlayEngine;
import com.pdfgen.domain.overlay.OverlayResult;
import com.pdfgen.domain.overlay.Variant;
import com.pdfgen.domain.overlay.VariantParser;
import com.pdfgen.domain.overlay.VariantSerializer;
import com.pdfgen.domain.tree.IdGenerator;
import com.pdfgen.domain.tree.StarterDocuments;
import com.pdfgen.domain.tree.Tree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Use cases: list documents, resolve a base or a variant chain into a validated {@link Document}, render it to PDF,
 * and the editor's open/save/create/delete (ADR-0022, ADR-0024, ADR-0025). Framework-free; wired by the bootstrap
 * configuration.
 */
public final class DocumentService {

    /** At most this many variant levels above a base (ADR-0025): cv-en -> cv-en-fullstack -> cv-en-fullstack-luxoft is 2. */
    public static final int MAX_VARIANT_DEPTH = 5;

    /** Ids of documents created in the app (also their file names). */
    private static final Pattern NEW_ID = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    private final DocumentRepository repository;
    private final PdfRenderer renderer;
    private final ThemeCatalog themes;
    private final VariantParser variantParser = new VariantParser();
    private final OverlayEngine overlayEngine = new OverlayEngine();
    private final DocumentMapper mapper = new DocumentMapper();
    private final OverlayDiff overlayDiff = new OverlayDiff();
    private final VariantSerializer variantSerializer = new VariantSerializer();

    public DocumentService(DocumentRepository repository, PdfRenderer renderer, ThemeCatalog themes) {
        this.repository = repository;
        this.renderer = renderer;
        this.themes = themes;
    }

    public List<DocumentSummary> list() {
        return repository.listIds().stream().map(this::summarize).toList();
    }

    /**
     * Loads {@code id} with its chain of parents (ADR-0025), validates every level from the base down (so an
     * error is blamed on the first file that breaks), and applies each variant to its parent's resolved tree.
     */
    public ResolvedDocument resolve(String id) {
        Resolved resolved = resolveChain(loadChain(id), true);
        return new ResolvedDocument(id, resolved.document().orElseThrow(), resolved.warnings());
    }

    public RenderedPdf render(String id) {
        ResolvedDocument resolved = resolve(id);
        byte[] pdf = renderer.render(resolved.document());
        return new RenderedPdf(resolved.fileName(), pdf, resolved.warnings());
    }

    // ---- editor (ADR-0022) ---------------------------------------------------------------------

    /**
     * What the editor shows: for a base its own tree; for a variant the <em>resolved</em> tree plus its parent's
     * resolved tree (to show what this variant overrides). {@code version} changes whenever any file of the
     * chain changes on disk.
     */
    public EditableDocument openForEdit(String id) {
        Chain chain = loadChain(id);
        Resolved resolved = resolveChain(chain, false);
        String version = String.join(":", chain.ids().stream().map(repository::fingerprint).toList());
        return new EditableDocument(id, chain.parentId(), resolved.tree(), resolved.parentTree(), version,
                repository.writtenByApp(id), resolved.warnings());
    }

    /**
     * Saves an edited document. A base is written as is (plain-text bullets get ids); for a variant only the
     * differences from its parent are written, as operations computed by {@link OverlayDiff}. Warns about
     * descendants (variants of it, at any depth) that no longer apply.
     *
     * @throws VersionConflictException if a file of the chain changed since {@code expectedVersion} was read
     */
    public EditableDocument save(String id, Map<String, Object> edited, String expectedVersion) {
        EditableDocument current = openForEdit(id);
        if (!current.version().equals(expectedVersion)) {
            throw new VersionConflictException(id);
        }
        Map<String, Object> draft = Tree.freezeMap(edited);
        if (VariantParser.isVariant(draft)) {
            throw new DocumentException("'extends' cannot be set from the editor");
        }
        if (current.parentId().isEmpty()) {
            Map<String, Object> tree = IdGenerator.assignMissingTextIds(draft);
            requireKnownTheme("'" + id + "'", mapper.map("'" + id + "'", tree));
            repository.save(id, tree);
        } else {
            String parentId = current.parentId().get();
            Map<String, Object> parentTree = current.parentTree().orElseThrow();
            String label = chainLabel(loadChain(id).ids());
            requireKnownTheme(label, mapper.map(label, draft));
            repository.save(id, variantFile(id, parentId, parentTree, draft));
        }
        List<String> warnings = new ArrayList<>(descendantsThatNoLongerApply(id));
        EditableDocument saved = openForEdit(id);
        warnings.addAll(saved.warnings());
        return saved.withWarnings(warnings);
    }

    /** Renders an unsaved draft (nothing is written). */
    public RenderedPdf preview(Map<String, Object> draft) {
        Map<String, Object> tree = Tree.freezeMap(draft);
        if (VariantParser.isVariant(tree)) {
            throw new DocumentException("Preview needs a resolved document, not a variant file");
        }
        Document document = mapper.map("(draft)", tree);
        return new RenderedPdf(document.meta().outputName().orElse("preview") + ".pdf", renderer.render(document),
                List.of());
    }

    /** Creates a new base document of {@code type} from its starter skeleton (ADR-0024, ADR-0026). */
    public void createDocument(String newId, DocumentType type) {
        requireNewId(newId, "Document");
        Map<String, Object> tree = StarterDocuments.of(type);
        mapper.map("'" + newId + "'", tree); // never write a file that cannot be loaded back
        repository.save(newId, tree);
    }

    /**
     * Creates an empty variant of {@code parentId}, which may be a base or another variant (ADR-0025). It looks
     * exactly like its parent until edited.
     */
    public void createVariant(String parentId, String newId) {
        requireNewId(newId, "Variant");
        Chain parent = loadChain(parentId);
        if (parent.depth() >= MAX_VARIANT_DEPTH) {
            throw new DocumentException("'" + parentId + "' is already " + parent.depth()
                    + " variant levels deep; at most " + MAX_VARIANT_DEPTH + " are supported");
        }
        repository.save(newId, variantSerializer.toTree(parentId, List.of()));
    }

    /**
     * Deletes a document, base or variant (ADR-0029). With {@code cascade}, every variant built on it (at any
     * level) is deleted too; without it, a document that others extend is refused. All or nothing, and a ZIP of
     * the deleted files is kept in {@code .history}.
     *
     * @param expectedIds the documents the user confirmed; if the real list differs (a variant was created
     *                    meanwhile), nothing is deleted and {@link StaleDeleteException} is thrown
     */
    public Deleted delete(String id, boolean cascade, Optional<List<String>> expectedIds) {
        if (!repository.exists(id)) {
            throw new DocumentNotFoundException(String.valueOf(id));
        }
        List<String> order = subtreeDeepestFirst(id);
        if (!cascade && order.size() > 1) {
            List<String> children = childrenOf().getOrDefault(id, List.of());
            throw new DocumentException("'" + id + "' cannot be deleted on its own: " + String.join(", ", children)
                    + (children.size() == 1 ? " extends" : " extend") + " it. Delete them together, or those first.");
        }
        if (expectedIds.isPresent() && !Set.copyOf(expectedIds.get()).equals(Set.copyOf(order))) {
            throw new StaleDeleteException(id, order);
        }
        String safetyBackup = repository.deleteAll(order, id);
        return new Deleted(order, safetyBackup);
    }

    /** The documents a cascading delete of {@code id} would remove, deepest variants first, {@code id} last. */
    public List<String> subtreeDeepestFirst(String id) {
        for (String other : repository.listIds()) {
            if (other.equals(id)) {
                continue;
            }
            try {
                repository.loadTree(other);
            } catch (RuntimeException e) {
                throw new DocumentException("Cannot tell which documents extend '" + id + "': '" + other
                        + "' cannot be read (" + e.getMessage() + "). Fix or remove that file first.");
            }
        }
        Map<String, List<String>> children = childrenOf();
        List<String> order = new ArrayList<>();
        collectDeepestFirst(id, children, new HashSet<>(), order);
        return List.copyOf(order);
    }

    private static void collectDeepestFirst(String id, Map<String, List<String>> children, Set<String> visited,
                                            List<String> order) {
        if (!visited.add(id)) {
            return;
        }
        for (String child : children.getOrDefault(id, List.of())) {
            collectDeepestFirst(child, children, visited, order);
        }
        order.add(id);
    }

    /** {@code deleted}: deepest first; {@code safetyBackup}: the ZIP of the deleted files, in the data folder. */
    public record Deleted(List<String> deleted, String safetyBackup) {
        public Deleted {
            deleted = List.copyOf(deleted);
        }
    }

    // ---- variant chains (ADR-0025) -------------------------------------------------------------

    /** A document and its parents, from the base down to the document: ids and raw trees. */
    private record Chain(List<String> ids, List<Map<String, Object>> trees) {
        /** Number of variant levels above the base (0 for a base). */
        int depth() {
            return ids.size() - 1;
        }

        Optional<String> parentId() {
            return ids.size() < 2 ? Optional.empty() : Optional.of(ids.get(ids.size() - 2));
        }
    }

    /** Walks {@code extends} up to the base. Fails fast on a missing parent, a cycle or too many levels. */
    private Chain loadChain(String id) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> ids = new ArrayList<>();
        List<Map<String, Object>> trees = new ArrayList<>();
        String current = id;
        while (true) {
            if (!seen.add(current)) {
                List<String> cycle = new ArrayList<>(seen);
                cycle.add(current);
                throw new DocumentException("Variant chain has a cycle: " + String.join(" -> ", cycle));
            }
            if (!current.equals(id) && !repository.exists(current)) {
                throw new DocumentException("'" + ids.getLast() + "' extends '" + current + "', which does not exist");
            }
            Map<String, Object> tree = repository.loadTree(current);
            ids.add(current);
            trees.add(tree);
            if (!VariantParser.isVariant(tree)) {
                break;
            }
            if (ids.size() > MAX_VARIANT_DEPTH) {
                throw new DocumentException("'" + id + "' is more than " + MAX_VARIANT_DEPTH
                        + " variant levels deep; at most " + MAX_VARIANT_DEPTH + " are supported (chain: "
                        + String.join(" -> ", ids) + " -> ...)");
            }
            current = variantParser.parse(current, tree).baseId();
        }
        return new Chain(List.copyOf(ids.reversed()), List.copyOf(trees.reversed()));
    }

    private record Resolved(Map<String, Object> tree, Optional<Map<String, Object>> parentTree,
                            Optional<Document> document, List<String> warnings) {
    }

    /**
     * Applies the chain top-down. With {@code validate}, every level is mapped (validated); the editor opens
     * documents without validating, so a broken one can still be fixed in the app.
     */
    private Resolved resolveChain(Chain chain, boolean validate) {
        Map<String, Object> tree = chain.trees().getFirst();
        Document document = validate ? mapper.map(chainLabel(chain.ids().subList(0, 1)), tree) : null;
        Map<String, Object> parent = null;
        List<String> warnings = new ArrayList<>();
        for (int level = 1; level < chain.ids().size(); level++) {
            String variantId = chain.ids().get(level);
            Variant variant = variantParser.parse(variantId, chain.trees().get(level));
            OverlayResult merged = overlayEngine.apply(tree, variant);
            warnings.addAll(merged.warnings()); // each warning already names its variant
            parent = tree;
            tree = merged.document();
            if (validate) {
                document = mapper.map(chainLabel(chain.ids().subList(0, level + 1)), tree);
            }
        }
        return new Resolved(tree, Optional.ofNullable(parent), Optional.ofNullable(document), warnings);
    }

    /** {@code 'cv-en' + variant 'cv-en-fullstack' + variant 'cv-en-fullstack-luxoft'}: names every file involved. */
    private static String chainLabel(List<String> ids) {
        StringBuilder label = new StringBuilder("'").append(ids.getFirst()).append("'");
        for (String variant : ids.subList(1, ids.size())) {
            label.append(" + variant '").append(variant).append("'");
        }
        return label.toString();
    }

    /** Parent id -> ids of the variants that extend it directly. */
    private Map<String, List<String>> childrenOf() {
        Map<String, List<String>> children = new LinkedHashMap<>();
        for (DocumentSummary summary : list()) {
            summary.parentId().ifPresent(parent ->
                    children.computeIfAbsent(parent, key -> new ArrayList<>()).add(summary.id()));
        }
        return children;
    }

    private List<String> descendantsThatNoLongerApply(String id) {
        Map<String, List<String>> children = childrenOf();
        List<String> warnings = new ArrayList<>();
        Deque<String> pending = new ArrayDeque<>(children.getOrDefault(id, List.of()));
        Set<String> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            String descendant = pending.removeFirst();
            if (!visited.add(descendant)) {
                continue;
            }
            try {
                resolve(descendant);
            } catch (DocumentException e) {
                warnings.add("Variant '" + descendant + "' no longer applies to the saved document: " + e.getMessage());
            }
            pending.addAll(children.getOrDefault(descendant, List.of()));
        }
        return warnings;
    }

    private void requireNewId(String newId, String what) {
        if (newId == null || !NEW_ID.matcher(newId).matches()) {
            throw new DocumentException(what + " name '" + newId
                    + "' may only contain lowercase letters, digits, '-', '_' and '.' (starting with a letter or digit)");
        }
        if (repository.exists(newId)) {
            throw new DocumentException("A document named '" + newId + "' already exists");
        }
    }

    private Map<String, Object> variantFile(String id, String parentId, Map<String, Object> parentTree,
                                            Map<String, Object> draft) {
        List<Operation> operations;
        try {
            operations = overlayDiff.diff(parentTree, draft);
        } catch (IllegalArgumentException e) {
            throw new DocumentException(e.getMessage());
        }
        Map<String, Object> file = variantSerializer.toTree(parentId, operations);
        // Safety net: the file we are about to write must reproduce exactly what the user sees.
        Map<String, Object> reproduced = overlayEngine.apply(parentTree, variantParser.parse(id, file)).document();
        if (!reproduced.equals(draft)) {
            throw new IllegalStateException("Internal error: variant operations do not reproduce the edited document");
        }
        return file;
    }

    /** The rules the editor needs for a document: allowed section kinds etc. (ADR-0026). */
    public DocumentTypeRules rules(EditableDocument document) {
        return DocumentTypeRules.of(typeOf(document));
    }

    /** Themes the document can use (ADR-0027). */
    public List<String> themes(EditableDocument document) {
        return themes.themes(typeOf(document));
    }

    private static DocumentType typeOf(EditableDocument document) {
        Object meta = document.tree().get(OverlayEngine.META);
        Object type = meta instanceof Map<?, ?> map ? map.get("type") : null;
        return DocumentType.fromKey(String.valueOf(type)).orElse(DocumentType.CV);
    }

    /** A typo in the theme is reported on the field when saving, not only when rendering (ADR-0027). */
    private void requireKnownTheme(String label, Document document) {
        List<String> available = themes.themes(document.meta().type());
        if (!available.contains(document.meta().theme())) {
            throw new DocumentValidationException(label, List.of(new Problem("meta.theme",
                    "unknown theme '" + document.meta().theme() + "' for " + document.meta().type().key()
                            + "; available: " + String.join(", ", available))));
        }
    }

    private DocumentSummary summarize(String id) {
        Map<String, Object> tree;
        try {
            tree = repository.loadTree(id);
        } catch (RuntimeException e) {
            return new DocumentSummary(id, Optional.empty(), Optional.empty(), Optional.of(e.getMessage()));
        }
        Optional<String> parent = VariantParser.isVariant(tree)
                ? Optional.of(String.valueOf(tree.get(VariantParser.EXTENDS)))
                : Optional.empty();
        try {
            // A whole chain has one type: the base's (variants cannot change meta.type, ADR-0015).
            Object meta = loadChain(id).trees().getFirst().get(OverlayEngine.META);
            String key = meta instanceof Map<?, ?> map ? String.valueOf(map.get("type")) : "";
            Optional<DocumentType> type = DocumentType.fromKey(key);
            return new DocumentSummary(id, parent, type, type.isPresent() ? Optional.empty()
                    : Optional.of("unknown document type '" + key + "' in meta.type"));
        } catch (RuntimeException e) {
            return new DocumentSummary(id, parent, Optional.empty(), Optional.of(e.getMessage()));
        }
    }

    /** A document opened in the editor. {@code parentTree} is the parent's resolved tree (variants only). */
    public record EditableDocument(String id, Optional<String> parentId, Map<String, Object> tree,
                                   Optional<Map<String, Object>> parentTree, String version, boolean writtenByApp,
                                   List<String> warnings) {
        public EditableDocument {
            warnings = List.copyOf(warnings);
        }

        public boolean variant() {
            return parentId.isPresent();
        }

        EditableDocument withWarnings(List<String> newWarnings) {
            return new EditableDocument(id, parentId, tree, parentTree, version, writtenByApp, newWarnings);
        }
    }

    /**
     * One entry of the document list. {@code parentId} is what a variant extends (a base or another variant);
     * {@code type} is the chain's type; {@code loadError} is set when the document cannot be read or its chain
     * is broken.
     */
    public record DocumentSummary(String id, Optional<String> parentId, Optional<DocumentType> type,
                                  Optional<String> loadError) {
        public boolean variant() {
            return parentId.isPresent();
        }
    }

    public record ResolvedDocument(String id, Document document, List<String> warnings) {
        public String fileName() {
            return document.meta().outputName().orElse(id) + ".pdf";
        }
    }

    public record RenderedPdf(String fileName, byte[] content, List<String> warnings) {
    }
}
