package com.pdfgen.domain.overlay;

import com.pdfgen.domain.overlay.Operation.ElementKind;
import com.pdfgen.domain.overlay.Operation.Placement;
import com.pdfgen.domain.tree.Tree;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns the raw tree of a variant file into a {@link Variant}. Only syntax is checked here
 * (keys, types); whether ids exist is checked when the operations are applied to a base.
 *
 * <pre>
 * extends: cv-en
 * meta: { outputName: CV_QA }
 * operations:
 *   - set:    { target: person, fields: { jobTitle: QA ENGINEER } }
 *   - add:    { into: experience, after: acme, item: { id: x, heading: ... } }
 *   - remove: { target: experience/acme/b3 }
 *   - order:  { target: sections, ids: [summary, experience] }
 * </pre>
 */
public final class VariantParser {

    public static final String EXTENDS = "extends";
    private static final Set<String> ROOT_KEYS = Set.of(EXTENDS, "meta", "operations");

    /** True if the raw tree is a variant (it declares a base). */
    public static boolean isVariant(Map<String, Object> tree) {
        return tree.containsKey(EXTENDS);
    }

    public Variant parse(String variantId, Map<String, Object> tree) {
        unknownKeys(tree, ROOT_KEYS, variantId, "file root");
        String baseId = string(tree, EXTENDS, variantId, "file root")
                .orElseThrow(() -> new OverlayException(variantId, "file root", "'extends' must name the base document"));
        if (baseId.equals(variantId)) {
            throw new OverlayException(variantId, "file root", "a variant cannot extend itself");
        }
        Map<String, Object> meta = optionalMap(tree, "meta", variantId, "file root").orElse(Map.of());

        Object rawOps = tree.getOrDefault("operations", List.of());
        if (!(rawOps instanceof List<?> opsList)) {
            throw new OverlayException(variantId, "file root", "'operations' must be a list");
        }
        List<Operation> operations = new ArrayList<>();
        for (int i = 0; i < opsList.size(); i++) {
            operations.add(parseOperation(variantId, i + 1, opsList.get(i)));
        }
        return new Variant(variantId, baseId, meta, operations);
    }

    private Operation parseOperation(String variantId, int index, Object raw) {
        String where = "operation #" + index;
        if (!(raw instanceof Map<?, ?> wrapper) || wrapper.size() != 1) {
            throw new OverlayException(variantId, where,
                    "each operation must be a single key: set, add, remove or order");
        }
        String type = String.valueOf(wrapper.keySet().iterator().next());
        if (!(wrapper.get(type) instanceof Map<?, ?> rawBody)) {
            throw new OverlayException(variantId, where, "'" + type + "' must contain a map of options");
        }
        Map<String, Object> body = Tree.freezeMap(rawBody);
        where = where + " (" + type + ")";
        return switch (type) {
            case "set" -> parseSet(variantId, index, where, body);
            case "add" -> parseAdd(variantId, index, where, body);
            case "remove" -> parseRemove(variantId, index, where, body);
            case "order" -> parseOrder(variantId, index, where, body);
            default -> throw new OverlayException(variantId, "operation #" + index,
                    "unknown operation '" + type + "' (expected set, add, remove or order)");
        };
    }

    private Operation parseSet(String variantId, int index, String where, Map<String, Object> body) {
        unknownKeys(body, Set.of("target", "fields", "unset"), variantId, where);
        IdPath target = path(body, "target", variantId, where, true);
        if (target.isRoot()) {
            throw new OverlayException(variantId, where, "'target' must name a section, item, bullet, person, meta or letter");
        }
        Map<String, Object> fields = optionalMap(body, "fields", variantId, where).orElse(Map.of());
        Object rawUnset = body.getOrDefault("unset", List.of());
        if (!(rawUnset instanceof List<?> unsetList) || !unsetList.stream().allMatch(String.class::isInstance)) {
            throw new OverlayException(variantId, where, "'unset' must be a list of field names");
        }
        List<String> unset = unsetList.stream().map(String.class::cast).toList();
        if (fields.isEmpty() && unset.isEmpty()) {
            throw new OverlayException(variantId, where, "'fields' must be a non-empty map (or give 'unset')");
        }
        if (fields.containsKey(Tree.ID) || unset.contains(Tree.ID)) {
            throw new OverlayException(variantId, where,
                    "'id' cannot be changed (variants address elements by id); remove and add instead");
        }
        return new Operation.Set(index, target, fields, unset);
    }

    private Operation parseAdd(String variantId, int index, String where, Map<String, Object> body) {
        unknownKeys(body, Set.of("into", "after", "before", "section", "item", "bullet"), variantId, where);
        IdPath into = path(body, "into", variantId, where, false);

        List<ElementKind> kinds = java.util.Arrays.stream(ElementKind.values())
                .filter(kind -> body.containsKey(kind.key())).toList();
        if (kinds.size() != 1) {
            throw new OverlayException(variantId, where, "exactly one of 'section', 'item' or 'bullet' is required");
        }
        ElementKind kind = kinds.getFirst();

        Optional<String> after = string(body, "after", variantId, where);
        Optional<String> before = string(body, "before", variantId, where);
        if (after.isPresent() && before.isPresent()) {
            throw new OverlayException(variantId, where, "use either 'after' or 'before', not both");
        }
        Placement placement = after.map(Placement::after)
                .or(() -> before.map(Placement::before))
                .orElse(Placement.end());
        return new Operation.Add(index, into, kind, body.get(kind.key()), placement);
    }

    private Operation parseRemove(String variantId, int index, String where, Map<String, Object> body) {
        unknownKeys(body, Set.of("target"), variantId, where);
        IdPath target = path(body, "target", variantId, where, true);
        if (target.isRoot()) {
            throw new OverlayException(variantId, where, "'target' must name a section, item or bullet");
        }
        return new Operation.Remove(index, target);
    }

    private Operation parseOrder(String variantId, int index, String where, Map<String, Object> body) {
        unknownKeys(body, Set.of("target", "ids"), variantId, where);
        IdPath target = path(body, "target", variantId, where, false);
        if (!(body.get("ids") instanceof List<?> ids) || ids.isEmpty()
                || !ids.stream().allMatch(String.class::isInstance)) {
            throw new OverlayException(variantId, where, "'ids' must be a non-empty list of ids");
        }
        return new Operation.Order(index, target, ids.stream().map(String.class::cast).toList());
    }

    // ---- small readers -------------------------------------------------------------------------

    private static void unknownKeys(Map<String, Object> map, Set<String> allowed, String variantId, String where) {
        List<String> unknown = map.keySet().stream().filter(key -> !allowed.contains(key)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw new OverlayException(variantId, where,
                    "unknown key(s) " + unknown + "; allowed: " + allowed.stream().sorted().toList());
        }
    }

    private static Optional<String> string(Map<String, Object> map, String key, String variantId, String where) {
        Object value = map.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new OverlayException(variantId, where, "'" + key + "' must be a non-empty text");
        }
        return Optional.of(text.trim());
    }

    @SuppressWarnings("unchecked")
    private static Optional<Map<String, Object>> optionalMap(Map<String, Object> map, String key,
                                                             String variantId, String where) {
        Object value = map.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof Map<?, ?>)) {
            throw new OverlayException(variantId, where, "'" + key + "' must be a map");
        }
        return Optional.of((Map<String, Object>) value);
    }

    private static IdPath path(Map<String, Object> map, String key, String variantId, String where, boolean required) {
        Optional<String> raw = string(map, key, variantId, where);
        if (raw.isEmpty()) {
            if (required) {
                throw new OverlayException(variantId, where, "'" + key + "' is required");
            }
            return IdPath.root();
        }
        try {
            return IdPath.parse(raw.get());
        } catch (IllegalArgumentException e) {
            throw new OverlayException(variantId, where, e.getMessage());
        }
    }
}
