package com.pdfgen.domain.overlay;

import com.pdfgen.domain.overlay.Operation.ElementKind;
import com.pdfgen.domain.overlay.Operation.Placement;
import com.pdfgen.domain.tree.Tree;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Computes the variant operations that turn a base document into an edited one (ADR-0022).
 * The editor always shows the resolved document; on save, this diff becomes the variant file.
 *
 * <p>Invariant (tested): {@code engine.apply(base, diff(base, edited)) == edited}.
 *
 * <p>Rules: elements are matched by id; changed fields become {@code set} (removed fields {@code unset});
 * lists containing elements without ids (plain bullets) are replaced whole; {@code order} is emitted only when
 * the relative order of shared ids changed; new elements are {@code add}ed after their previous sibling;
 * a section whose kind changed is removed and added again. Output follows document order (deterministic).
 */
public final class OverlayDiff {

    private static final List<String> CHILD_KEYS = List.of("sections", "items", "bullets");
    private static final List<ElementKind> KINDS = List.of(ElementKind.SECTION, ElementKind.ITEM, ElementKind.BULLET);

    public List<Operation> diff(Map<String, Object> base, Map<String, Object> edited) {
        Builder ops = new Builder();
        for (String root : OverlayEngine.RESERVED_ROOTS) {
            diffReserved(base, edited, root, ops);
        }
        diffChildren(list(base, "sections"), list(edited, "sections"), IdPath.root(), 0, ops);
        return ops.operations;
    }

    private void diffReserved(Map<String, Object> base, Map<String, Object> edited, String root, Builder ops) {
        Map<String, Object> before = map(base, root);
        Map<String, Object> after = map(edited, root);
        if (root.equals(OverlayEngine.META) && !Objects.equals(before.get("type"), after.get("type"))) {
            throw new IllegalArgumentException("A variant cannot change the document type");
        }
        Map<String, Object> changed = changedFields(before, after, Set.of("type"));
        List<String> unset = removedFields(before, after, Set.of("type"));
        if (!changed.isEmpty() || !unset.isEmpty()) {
            ops.set(new IdPath(List.of(root)), changed, unset);
        }
    }

    /** Diffs the children list of the element at {@code parent}; elements of the list live at {@code depth}. */
    private void diffChildren(List<Object> before, List<Object> after, IdPath parent, int depth, Builder ops) {
        if (before.equals(after)) {
            return;
        }
        if (!allHaveIds(before) || !allHaveIds(after)) {
            if (parent.isRoot()) {
                throw new IllegalArgumentException("every section needs an id");
            }
            ops.set(parent, Map.of(CHILD_KEYS.get(depth), after), List.of());
            return;
        }
        Map<String, Object> beforeById = byId(before);
        Map<String, Object> afterById = byId(after);
        Set<String> common = new LinkedHashSet<>();
        for (String id : afterById.keySet()) {
            if (beforeById.containsKey(id) && !kindChanged(beforeById.get(id), afterById.get(id), depth)) {
                common.add(id);
            }
        }

        for (String id : beforeById.keySet()) {
            if (!common.contains(id)) {
                ops.remove(child(parent, id));
            }
        }
        for (String id : common) {
            diffElement(asMap(beforeById.get(id)), asMap(afterById.get(id)), child(parent, id), depth, ops);
        }
        List<String> baseOrder = beforeById.keySet().stream().filter(common::contains).toList();
        List<String> editedOrder = List.copyOf(common);
        if (!baseOrder.equals(editedOrder)) {
            ops.order(parent, editedOrder);
        }
        addNewElements(after, common, parent, depth, ops);
    }

    private void addNewElements(List<Object> after, Set<String> common, IdPath parent, int depth, Builder ops) {
        List<String> ids = after.stream().map(element -> Tree.idOf(element).orElseThrow()).toList();
        Optional<String> firstCommon = ids.stream().filter(common::contains).findFirst();
        for (int i = 0; i < ids.size(); i++) {
            if (common.contains(ids.get(i))) {
                continue;
            }
            // Everything before index i is already in place (shared or added in an earlier step).
            Placement placement = i > 0 ? Placement.after(ids.get(i - 1))
                    : firstCommon.map(Placement::before).orElse(Placement.end());
            ops.add(parent, KINDS.get(depth), after.get(i), placement);
        }
    }

    private void diffElement(Map<String, Object> before, Map<String, Object> after, IdPath path, int depth,
                             Builder ops) {
        String childKey = depth + 1 < CHILD_KEYS.size() ? CHILD_KEYS.get(depth + 1) : null;
        Set<String> skip = childKey == null ? Set.of(Tree.ID) : Set.of(Tree.ID, childKey);
        Map<String, Object> changed = changedFields(before, after, skip);
        List<String> unset = removedFields(before, after, skip);
        if (!changed.isEmpty() || !unset.isEmpty()) {
            ops.set(path, changed, unset);
        }
        if (childKey != null) {
            diffChildren(list(before, childKey), list(after, childKey), path, depth + 1, ops);
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static Map<String, Object> changedFields(Map<String, Object> before, Map<String, Object> after,
                                                     Set<String> skip) {
        Map<String, Object> changed = new LinkedHashMap<>();
        after.forEach((key, value) -> {
            if (!skip.contains(key) && !Objects.equals(before.get(key), value)) {
                changed.put(key, value);
            }
        });
        return changed;
    }

    private static List<String> removedFields(Map<String, Object> before, Map<String, Object> after,
                                              Set<String> skip) {
        return before.keySet().stream().filter(key -> !skip.contains(key) && !after.containsKey(key)).toList();
    }

    private static boolean kindChanged(Object before, Object after, int depth) {
        return depth == 0 && !Objects.equals(asMap(before).get("kind"), asMap(after).get("kind"));
    }

    private static boolean allHaveIds(List<Object> elements) {
        return elements.stream().allMatch(element -> Tree.idOf(element).isPresent());
    }

    private static Map<String, Object> byId(List<Object> elements) {
        Map<String, Object> byId = new LinkedHashMap<>();
        elements.forEach(element -> byId.put(Tree.idOf(element).orElseThrow(), element));
        return byId;
    }

    private static IdPath child(IdPath parent, String id) {
        List<String> segments = new ArrayList<>(parent.segments());
        segments.add(id);
        return new IdPath(segments);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> node, String key) {
        return node.get(key) instanceof List<?> list ? (List<Object>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> node, String key) {
        return node.get(key) instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        return (Map<String, Object>) node;
    }

    /** Numbers operations 1..n in emission order. */
    private static final class Builder {
        private final List<Operation> operations = new ArrayList<>();

        void set(IdPath target, Map<String, Object> fields, List<String> unset) {
            operations.add(new Operation.Set(next(), target, fields, unset));
        }

        void remove(IdPath target) {
            operations.add(new Operation.Remove(next(), target));
        }

        void order(IdPath target, List<String> ids) {
            operations.add(new Operation.Order(next(), target, ids));
        }

        void add(IdPath into, ElementKind kind, Object element, Placement placement) {
            operations.add(new Operation.Add(next(), into, kind, element, placement));
        }

        private int next() {
            return operations.size() + 1;
        }
    }
}
