package com.pdfgen.domain.mapping;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Typed, problem-collecting reader over one map of the raw tree. Every getter records a readable
 * problem (with the field path) instead of throwing, so one validation run reports everything.
 */
final class Fields {

    private final Map<String, Object> map;
    private final String path;
    private final List<Problem> problems;

    Fields(Map<String, Object> map, String path, List<Problem> problems, Set<String> allowedKeys) {
        this.map = map;
        this.path = path;
        this.problems = problems;
        Set<String> unknown = new TreeSet<>(map.keySet());
        unknown.removeAll(allowedKeys);
        if (!unknown.isEmpty()) {
            problems.add(new Problem(path, "unknown field(s) " + unknown + "; allowed: " + new TreeSet<>(allowedKeys)));
        }
    }

    String path() {
        return path;
    }

    String child(String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    void problem(String message) {
        problems.add(new Problem(path, message));
    }

    Optional<String> optionalText(String key) {
        Object value = map.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof String text) {
            return text.isBlank() ? Optional.empty() : Optional.of(text.strip());
        }
        problems.add(new Problem(child(key), "must be text, found " + describe(value)));
        return Optional.empty();
    }

    /** Returns the text, or {@code ""} after recording a problem if missing. */
    String requiredText(String key) {
        if (!map.containsKey(key)) {
            problems.add(new Problem(child(key), "is required"));
            return "";
        }
        Optional<String> value = optionalText(key);
        if (value.isEmpty() && map.get(key) instanceof String) {
            problems.add(new Problem(child(key), "must not be empty"));
        }
        return value.orElse("");
    }

    boolean flag(String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        problems.add(new Problem(child(key), "must be true or false, found " + describe(value)));
        return defaultValue;
    }

    Optional<Map<String, Object>> optionalMap(String key) {
        Object value = map.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Map<?, ?> nested) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) nested;
            return Optional.of(typed);
        }
        problems.add(new Problem(child(key), "must be a map, found " + describe(value)));
        return Optional.empty();
    }

    Map<String, Object> requiredMap(String key) {
        if (!map.containsKey(key)) {
            problems.add(new Problem(child(key), "is required"));
        }
        return optionalMap(key).orElse(Map.of());
    }

    List<Object> list(String key, boolean required) {
        Object value = map.get(key);
        if (value == null) {
            if (required) {
                problems.add(new Problem(child(key), "is required"));
            }
            return List.of();
        }
        if (value instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<Object> typed = (List<Object>) list;
            return typed;
        }
        problems.add(new Problem(child(key), "must be a list, found " + describe(value)));
        return List.of();
    }

    static String describe(Object value) {
        return switch (value) {
            case Map<?, ?> ignored -> "a map";
            case List<?> ignored -> "a list";
            case Boolean bool -> "'" + bool + "'";
            default -> "'" + value + "'";
        };
    }
}
