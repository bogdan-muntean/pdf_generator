package com.pdfgen.domain;

import com.pdfgen.domain.tree.Tree;

import java.util.List;
import java.util.Map;

/** Small, readable raw trees for domain tests. */
public final class TestTrees {

    private TestTrees() {
    }

    public static Map<String, Object> baseCv() {
        return Tree.freezeMap(Map.of(
                "meta", Map.of("type", "cv", "lang", "en", "theme", "classic"),
                "person", Map.of("name", "Jane Doe", "jobTitle", "DEVELOPER"),
                "sections", List.of(
                        Map.of("id", "summary", "kind", "text", "title", "SUMMARY",
                                "items", List.of("Hello.")),
                        Map.of("id", "experience", "kind", "entries", "title", "WORK EXPERIENCE",
                                "items", List.of(
                                        Map.of("id", "acme", "heading", "Acme – Dev", "bullets", List.of(
                                                Map.of("id", "b1", "text", "First;"),
                                                "Second, plain text;",
                                                Map.of("id", "b3", "text", "Third."))),
                                        Map.of("id", "globex", "heading", "Globex – Intern",
                                                "bullets", List.of("Only bullet.")))),
                        Map.of("id", "skills", "kind", "list", "title", "SKILLS",
                                "items", List.of("Java;", "SQL.")))));
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> sections(Map<String, Object> tree) {
        return (List<Map<String, Object>>) tree.get("sections");
    }

    public static Map<String, Object> section(Map<String, Object> tree, String id) {
        return sections(tree).stream().filter(s -> id.equals(s.get("id"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> items(Map<String, Object> tree, String sectionId) {
        return (List<Map<String, Object>>) section(tree, sectionId).get("items");
    }

    public static List<String> sectionIds(Map<String, Object> tree) {
        return sections(tree).stream().map(s -> (String) s.get("id")).toList();
    }
}
