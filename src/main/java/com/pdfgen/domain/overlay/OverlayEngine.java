package com.pdfgen.domain.overlay;

import com.pdfgen.domain.overlay.Operation.Add;
import com.pdfgen.domain.overlay.Operation.Order;
import com.pdfgen.domain.overlay.Operation.Remove;
import com.pdfgen.domain.tree.Tree;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Applies a {@link Variant} to the raw tree of its base document (ADR-0005, ADR-0013).
 *
 * <p>The tree has three addressable levels, each a list of maps identified by {@code id}:
 * {@code sections} (document) → {@code items} (section) → {@code bullets} (item).
 * {@code meta}, {@code person} and {@code letter} are reserved roots that only accept {@code set} (ADR-0015, ADR-0026).
 *
 * <p>The engine is stateless and never mutates its input: every operation returns a new tree.
 */
public final class OverlayEngine {

    public static final String PERSON = "person";
    public static final String META = "meta";
    /** Cover letters only (ADR-0026): salutation, closing, signature, date, subject. */
    public static final String LETTER = "letter";
    /** Fixed order: variant files and diffs list them in this order (deterministic). */
    public static final List<String> RESERVED_ROOTS = List.of(META, PERSON, LETTER);
    /** Meta fields a variant may change; {@code type} is fixed by the base. */
    public static final Set<String> OVERRIDABLE_META = Set.of("theme", "lang", "outputName");

    /** Child-list key per depth: document → sections, section → items, item → bullets. */
    private static final List<String> CHILD_KEYS = List.of("sections", "items", "bullets");

    public OverlayResult apply(Map<String, Object> base, Variant variant) {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> document = base;
        if (!variant.meta().isEmpty()) {
            document = setReserved(document, META, variant.meta(), List.of(), new Context(variant.id(), "meta header"));
        }
        for (Operation operation : variant.operations()) {
            Context ctx = new Context(variant.id(), operation.label());
            document = switch (operation) {
                case Operation.Set set -> applySet(document, set, ctx);
                case Add add -> applyAdd(document, add, ctx);
                case Remove remove -> applyRemove(document, remove, ctx);
                case Order order -> applyOrder(document, order, ctx, warnings);
            };
        }
        return new OverlayResult(document, warnings);
    }

    // ---- operations ----------------------------------------------------------------------------

    private Map<String, Object> applySet(Map<String, Object> doc, Operation.Set op, Context ctx) {
        IdPath target = op.target();
        if (target.depth() == 1 && RESERVED_ROOTS.contains(target.first())) {
            return setReserved(doc, target.first(), op.fields(), op.unset(), ctx);
        }
        rejectReserved(target, ctx);
        return updateAt(doc, target, 0, element -> {
            if (!(element instanceof Map<?, ?>)) {
                throw ctx.fail("'" + target + "' is plain text; give it an id ({id, text}) to change it");
            }
            return Tree.without(Tree.merge(asMap(element), op.fields()), op.unset());
        }, ctx);
    }

    private Map<String, Object> applyAdd(Map<String, Object> doc, Add op, Context ctx) {
        IdPath into = op.into();
        rejectReserved(into, ctx);
        if (into.depth() != op.kind().parentDepth()) {
            throw ctx.fail("'" + op.kind().key() + "' must be added into "
                    + expectedParent(op.kind()) + ", but 'into' is '" + into + "'");
        }
        Object element = Tree.freeze(op.element());
        if (op.kind() != Operation.ElementKind.BULLET && Tree.idOf(element).isEmpty()) {
            throw ctx.fail("the new " + op.kind().key() + " must be a map with an 'id'");
        }
        String childKey = CHILD_KEYS.get(into.depth());
        return updateAt(doc, into, 0, parent -> {
            Map<String, Object> parentMap = asMap(parent);
            List<Object> children = new ArrayList<>(childList(parentMap, childKey, into, ctx, true));
            Tree.idOf(element).ifPresent(id -> {
                if (indexOf(children, id) >= 0) {
                    throw ctx.fail("id '" + id + "' already exists in '" + into + "'");
                }
            });
            int position = switch (op.placement().kind()) {
                case END -> children.size();
                case AFTER -> anchorIndex(children, op, into, ctx) + 1;
                case BEFORE -> anchorIndex(children, op, into, ctx);
            };
            children.add(position, element);
            return Tree.with(parentMap, childKey, children);
        }, ctx);
    }

    private Map<String, Object> applyRemove(Map<String, Object> doc, Remove op, Context ctx) {
        IdPath target = op.target();
        rejectReserved(target, ctx);
        IdPath parentPath = target.parent();
        String childKey = CHILD_KEYS.get(parentPath.depth());
        return updateAt(doc, parentPath, 0, parent -> {
            Map<String, Object> parentMap = asMap(parent);
            List<Object> children = new ArrayList<>(childList(parentMap, childKey, parentPath, ctx, false));
            children.remove(requireIndex(children, target.last(), parentPath, ctx));
            return Tree.with(parentMap, childKey, children);
        }, ctx);
    }

    private Map<String, Object> applyOrder(Map<String, Object> doc, Order op, Context ctx, List<String> warnings) {
        IdPath target = op.target();
        rejectReserved(target, ctx);
        if (target.depth() >= CHILD_KEYS.size()) {
            throw ctx.fail("'" + target + "' has no children to order");
        }
        Set<String> listed = new LinkedHashSet<>(op.ids());
        if (listed.size() != op.ids().size()) {
            throw ctx.fail("'ids' contains duplicates: " + op.ids());
        }
        String childKey = CHILD_KEYS.get(target.depth());
        return updateAt(doc, target, 0, parent -> {
            Map<String, Object> parentMap = asMap(parent);
            List<Object> children = childList(parentMap, childKey, target, ctx, false);
            List<Object> reordered = new ArrayList<>(children.size());
            for (String id : listed) {
                reordered.add(children.get(requireIndex(children, id, target, ctx)));
            }
            List<String> unlisted = new ArrayList<>();
            for (Object child : children) {
                boolean isListed = Tree.idOf(child).filter(listed::contains).isPresent();
                if (!isListed) {
                    reordered.add(child);
                    unlisted.add(Tree.idOf(child).orElse("<text without id>"));
                }
            }
            if (!unlisted.isEmpty()) {
                warnings.add("Variant '" + ctx.variantId() + "', " + ctx.where() + ": in '" + target
                        + "' these were not listed and keep their base order after the listed ones: " + unlisted);
            }
            return Tree.with(parentMap, childKey, reordered);
        }, ctx);
    }

    private Map<String, Object> setReserved(Map<String, Object> doc, String root, Map<String, Object> fields,
                                            List<String> unset, Context ctx) {
        if (root.equals(META)) {
            List<String> forbidden = java.util.stream.Stream.concat(fields.keySet().stream(), unset.stream())
                    .filter(key -> !OVERRIDABLE_META.contains(key)).distinct().sorted().toList();
            if (!forbidden.isEmpty()) {
                throw ctx.fail("meta field(s) " + forbidden + " cannot be changed by a variant; allowed: "
                        + OVERRIDABLE_META.stream().sorted().toList());
            }
        }
        Object current = doc.getOrDefault(root, Map.of());
        if (!(current instanceof Map<?, ?>)) {
            throw ctx.fail("'" + root + "' in the base is not a map");
        }
        return Tree.with(doc, root, Tree.without(Tree.merge(asMap(current), fields), unset));
    }

    // ---- tree navigation -----------------------------------------------------------------------

    /** Applies {@code change} to the node at {@code path} (relative to {@code node} at {@code depth}). */
    private Map<String, Object> updateAt(Map<String, Object> node, IdPath path, int depth,
                                         UnaryOperator<Object> change, Context ctx) {
        if (path.isRoot()) {
            return asMap(change.apply(node));
        }
        if (depth >= CHILD_KEYS.size()) {
            throw ctx.fail("path is too deep; the maximum is section/item/bullet");
        }
        String childKey = CHILD_KEYS.get(depth);
        List<Object> children = new ArrayList<>(childList(node, childKey, path, ctx, false));
        int index = requireIndex(children, path.first(), path, ctx);
        Object child = children.get(index);
        Object updated;
        if (path.depth() == 1) {
            updated = change.apply(child);
        } else {
            if (!(child instanceof Map<?, ?>)) {
                throw ctx.fail("'" + path.first() + "' has no children");
            }
            updated = updateAt(asMap(child), path.tail(), depth + 1, change, ctx);
        }
        children.set(index, updated);
        return Tree.with(node, childKey, children);
    }

    private static List<Object> childList(Map<String, Object> node, String key, IdPath where, Context ctx,
                                          boolean createIfMissing) {
        Object value = node.get(key);
        if (value == null && createIfMissing) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw ctx.fail("'" + where + "' has no '" + key + "' list");
        }
        @SuppressWarnings("unchecked")
        List<Object> children = (List<Object>) list;
        return children;
    }

    private static int indexOf(List<Object> children, String id) {
        for (int i = 0; i < children.size(); i++) {
            if (Tree.idOf(children.get(i)).filter(id::equals).isPresent()) {
                return i;
            }
        }
        return -1;
    }

    private static int requireIndex(List<Object> children, String id, IdPath where, Context ctx) {
        int index = indexOf(children, id);
        if (index < 0) {
            List<String> known = children.stream().map(Tree::idOf).flatMap(java.util.Optional::stream).toList();
            throw ctx.fail("no element with id '" + id + "' in '" + where + "'. Known ids: " + known
                    + ". (Plain-text bullets/items need an id in the base to be targeted: {id: ..., text: ...})");
        }
        return index;
    }

    private static int anchorIndex(List<Object> children, Add op, IdPath into, Context ctx) {
        return requireIndex(children, op.placement().anchorId().orElseThrow(), into, ctx);
    }

    private static void rejectReserved(IdPath path, Context ctx) {
        if (!path.isRoot() && RESERVED_ROOTS.contains(path.first())) {
            throw ctx.fail("'" + path.first() + "' only supports 'set'");
        }
    }

    private static String expectedParent(Operation.ElementKind kind) {
        return switch (kind) {
            case SECTION -> "the document (omit 'into')";
            case ITEM -> "a section ('into: <sectionId>')";
            case BULLET -> "an item ('into: <sectionId>/<itemId>')";
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        return (Map<String, Object>) node;
    }

    private record Context(String variantId, String where) {
        OverlayException fail(String problem) {
            return new OverlayException(variantId, where, problem);
        }
    }
}
