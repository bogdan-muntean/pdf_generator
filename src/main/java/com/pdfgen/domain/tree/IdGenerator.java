package com.pdfgen.domain.tree;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stable ids for elements (ADR-0022, principle 4): readable slugs, unique among siblings, never regenerated.
 */
public final class IdGenerator {

    private static final int MAX_LENGTH = 40;
    private static final int BULLET_WORDS = 4;

    private IdGenerator() {
    }

    /** {@code "Ștefan's Master – 2023"} → {@code "stefan-s-master-2023"}; never empty. */
    public static String slug(String text, int maxWords) {
        String ascii = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase();
        String joined = Arrays.stream(ascii.split("[^a-z0-9]+"))
                .filter(word -> !word.isEmpty())
                .limit(maxWords)
                .collect(Collectors.joining("-"));
        if (joined.length() > MAX_LENGTH) {
            joined = joined.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        }
        return joined.isEmpty() ? "item" : joined;
    }

    /** {@code base}, or {@code base-2}, {@code base-3}… whichever is not taken yet. */
    public static String unique(String base, Set<String> taken) {
        if (!taken.contains(base)) {
            return base;
        }
        for (int suffix = 2; ; suffix++) {
            String candidate = base + "-" + suffix;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
    }

    /**
     * Gives an id to every plain-text bullet, list item and paragraph of a base document, so variants
     * can target them individually. Existing ids are kept; result is deterministic.
     */
    public static Map<String, Object> assignMissingTextIds(Map<String, Object> document) {
        if (!(document.get("sections") instanceof List<?> sections)) {
            return document;
        }
        List<Object> newSections = new ArrayList<>();
        for (Object section : sections) {
            newSections.add(section instanceof Map<?, ?> map ? withSectionIds(asMap(map)) : section);
        }
        return Tree.with(document, "sections", newSections);
    }

    private static Map<String, Object> withSectionIds(Map<String, Object> section) {
        Object kind = section.get("kind");
        if (!(section.get("items") instanceof List<?> items)) {
            return section;
        }
        if ("text".equals(kind) || "list".equals(kind)) {
            return Tree.with(section, "items", withTextIds(items));
        }
        if ("entries".equals(kind)) {
            List<Object> newItems = new ArrayList<>();
            for (Object item : items) {
                if (item instanceof Map<?, ?> entry && entry.get("bullets") instanceof List<?> bullets) {
                    newItems.add(Tree.with(asMap(entry), "bullets", withTextIds(bullets)));
                } else {
                    newItems.add(item);
                }
            }
            return Tree.with(section, "items", newItems);
        }
        return section;
    }

    private static List<Object> withTextIds(List<?> elements) {
        Set<String> taken = new HashSet<>();
        elements.forEach(element -> Tree.idOf(element).ifPresent(taken::add));
        List<Object> result = new ArrayList<>();
        for (Object element : elements) {
            if (element instanceof String text) {
                String id = unique(slug(text, BULLET_WORDS), taken);
                taken.add(id);
                Map<String, Object> withId = new LinkedHashMap<>();
                withId.put(Tree.ID, id);
                withId.put("text", text);
                result.add(withId);
            } else {
                result.add(element);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
