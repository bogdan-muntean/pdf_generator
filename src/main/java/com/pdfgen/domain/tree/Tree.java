package com.pdfgen.domain.tree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Helpers for the raw document tree: nested {@code Map<String, Object>} / {@code List<Object>} /
 * {@code String} / {@code Boolean}. Every tree handed around by the domain is deeply immutable;
 * "modifications" always return new copies (copy-on-write, see ADR-0013).
 */
public final class Tree {

    /** Key holding the stable identifier of a list element. */
    public static final String ID = "id";

    private Tree() {
    }

    /**
     * Returns a deep, unmodifiable copy of {@code node}. Map order is preserved, {@code null} map values
     * are dropped (an empty YAML field means "absent"), numbers become strings.
     *
     * @throws IllegalArgumentException for {@code null} list elements or unsupported value types
     */
    public static Object freeze(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (value != null) {
                    copy.put(String.valueOf(key), freeze(value));
                }
            });
            return Collections.unmodifiableMap(copy);
        }
        if (node instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object element : list) {
                if (element == null) {
                    throw new IllegalArgumentException("lists must not contain empty elements");
                }
                copy.add(freeze(element));
            }
            return Collections.unmodifiableList(copy);
        }
        if (node instanceof String || node instanceof Boolean) {
            return node;
        }
        if (node instanceof Number number) {
            return number.toString();
        }
        throw new IllegalArgumentException("unsupported value type: " + node.getClass().getSimpleName());
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> freezeMap(Map<?, ?> map) {
        return (Map<String, Object>) freeze(map);
    }

    /** The {@code id} of a list element, if the element is a map carrying a textual id. */
    public static Optional<String> idOf(Object element) {
        if (element instanceof Map<?, ?> map && map.get(ID) instanceof String id) {
            return Optional.of(id);
        }
        return Optional.empty();
    }

    /** Copy of {@code map} with {@code key} set to {@code value}. */
    public static Map<String, Object> with(Map<String, Object> map, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(map);
        copy.put(key, freeze(value));
        return Collections.unmodifiableMap(copy);
    }

    /** Copy of {@code map} without the given keys (same instance if none of them is present). */
    public static Map<String, Object> without(Map<String, Object> map, java.util.Collection<String> keys) {
        if (keys.stream().noneMatch(map::containsKey)) {
            return map;
        }
        Map<String, Object> copy = new LinkedHashMap<>(map);
        keys.forEach(copy::remove);
        return Collections.unmodifiableMap(copy);
    }

    /** Copy of {@code map} with all {@code fields} shallow-replaced. */
    public static Map<String, Object> merge(Map<String, Object> map, Map<String, Object> fields) {
        Map<String, Object> copy = new LinkedHashMap<>(map);
        fields.forEach((key, value) -> copy.put(key, freeze(value)));
        return Collections.unmodifiableMap(copy);
    }
}
