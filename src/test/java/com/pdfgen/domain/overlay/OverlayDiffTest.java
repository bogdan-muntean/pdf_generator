package com.pdfgen.domain.overlay;

import com.pdfgen.domain.tree.Tree;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static com.pdfgen.domain.TestTrees.baseCv;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The invariant {@code apply(base, diff(base, edited)) == edited}, checked through the full file round trip:
 * diff → serialize → parse → apply (ADR-0022).
 */
class OverlayDiffTest {

    private final OverlayDiff diff = new OverlayDiff();
    private final OverlayEngine engine = new OverlayEngine();
    private final VariantSerializer serializer = new VariantSerializer();
    private final VariantParser parser = new VariantParser();

    /** Applies {@code edit} to a mutable deep copy of the base and returns it frozen. */
    private static Map<String, Object> edited(UnaryOperator<Map<String, Object>> edit) {
        return Tree.freezeMap(edit.apply(mutable(baseCv())));
    }

    private List<Operation> assertRoundTrip(Map<String, Object> edited) {
        Map<String, Object> base = baseCv();
        List<Operation> operations = diff.diff(base, edited);
        Map<String, Object> variantFile = serializer.toTree("base", operations);
        Variant variant = parser.parse("variant", variantFile);
        assertThat(engine.apply(base, variant).document()).isEqualTo(edited);
        return operations;
    }

    @Test
    void identicalDocumentsNeedNoOperations() {
        assertThat(diff.diff(baseCv(), baseCv())).isEmpty();
    }

    @Test
    void fieldChangesBecomeSetsOnTheExactTarget() {
        var operations = assertRoundTrip(edited(doc -> {
            person(doc).put("jobTitle", "QA ENGINEER");
            section(doc, "experience").put("title", "PROFESSIONAL EXPERIENCE");
            item(doc, "experience", "acme").put("heading", "Acme – Senior Dev");
            bullet(doc, "experience", "acme", "b3").put("text", "Reworded.");
            return doc;
        }));
        // acme's bullets include a plain string (no id), so the bullet change replaces acme's whole list
        assertThat(operations).extracting(op -> op.getClass().getSimpleName() + " " + target(op)).containsExactly(
                "Set person", "Set experience", "Set experience/acme", "Set experience/acme");
    }

    @Test
    void bulletWithIdIsTargetedDirectlyWhenAllSiblingsHaveIds() {
        Map<String, Object> base = Tree.freezeMap(edited(doc -> {
            list(item(doc, "experience", "acme"), "bullets").set(1, Map.of("id", "b2", "text", "Second;"));
            return doc;
        }));
        Map<String, Object> changed = Tree.freezeMap(mutableEdit(base, doc -> {
            bullet(doc, "experience", "acme", "b3").put("text", "Reworded.");
            return doc;
        }));
        var operations = diff.diff(base, changed);
        assertThat(operations).singleElement().satisfies(op -> assertThat(target(op)).isEqualTo("experience/acme/b3"));
        assertThat(engine.apply(base, parser.parse("v", serializer.toTree("base", operations))).document())
                .isEqualTo(changed);
    }

    private static Map<String, Object> mutableEdit(Map<String, Object> tree, UnaryOperator<Map<String, Object>> edit) {
        return edit.apply(mutable(tree));
    }

    @Test
    void removedFieldsAreUnset() {
        var operations = assertRoundTrip(edited(doc -> {
            person(doc).remove("jobTitle");
            return doc;
        }));
        assertThat(operations).singleElement().isInstanceOfSatisfying(Operation.Set.class,
                set -> assertThat(set.unset()).containsExactly("jobTitle"));
    }

    @Test
    void metaOverridesButNoTypeChange() {
        assertRoundTrip(edited(doc -> {
            asMap(doc.get("meta")).put("outputName", "CV_QA");
            return doc;
        }));
        assertThatThrownBy(() -> diff.diff(baseCv(), edited(doc -> {
            asMap(doc.get("meta")).put("type", "letter");
            return doc;
        }))).hasMessageContaining("document type");
    }

    @Test
    void removalsAdditionsAndReordering() {
        var operations = assertRoundTrip(edited(doc -> {
            List<Object> sections = list(doc, "sections");
            sections.removeIf(s -> "skills".equals(asMap(s).get("id")));
            sections.addFirst(Map.of("id", "intro", "kind", "list", "title", "INTRO", "items", List.of("Hi.")));
            sections.add(Map.of("id", "awards", "kind", "list", "title", "AWARDS", "items", List.of("Prize.")));
            List<Object> items = list(section(doc, "experience"), "items");
            items.add(0, items.remove(1)); // globex before acme
            items.add(1, Map.of("id", "initech", "heading", "Initech – Intern"));
            return doc;
        }));
        assertThat(operations).extracting(op -> op.getClass().getSimpleName() + " " + target(op)).containsExactly(
                "Remove skills", "Order experience", "Add experience", "Add sections", "Add sections");
    }

    @Test
    void sectionsAddedOnlyAtTheStartAnchorBeforeTheFirstSharedOne() {
        assertRoundTrip(edited(doc -> {
            List<Object> sections = list(doc, "sections");
            sections.add(0, Map.of("id", "a", "kind", "list", "title", "A", "items", List.of("x")));
            sections.add(1, Map.of("id", "b", "kind", "list", "title", "B", "items", List.of("y")));
            return doc;
        }));
    }

    @Test
    void bulletsWithIdsAreGranularPlainOnesReplaceTheList() {
        var granular = assertRoundTrip(edited(doc -> {
            Map<String, Object> acme = item(doc, "experience", "acme");
            List<Object> bullets = list(acme, "bullets");
            bullets.removeIf(b -> b instanceof Map<?, ?> m && "b1".equals(m.get("id")));
            return doc;
        }));
        assertThat(granular).singleElement().isInstanceOf(Operation.Set.class);
        // base bullets contain a plain string, so the whole list is replaced (still exact)
        assertThat(((Operation.Set) granular.getFirst()).fields()).containsKey("bullets");

        var plainList = assertRoundTrip(edited(doc -> {
            list(section(doc, "skills"), "items").set(0, "Kotlin;");
            return doc;
        }));
        assertThat(plainList).singleElement().satisfies(op -> assertThat(target(op)).isEqualTo("skills"));
    }

    @Test
    void sectionKindChangeIsRemoveAndAdd() {
        var operations = assertRoundTrip(edited(doc -> {
            Map<String, Object> skills = section(doc, "skills");
            skills.put("kind", "categories");
            skills.put("items", List.of(Map.of("id", "lang", "label", "Languages", "values", List.of("Java"))));
            return doc;
        }));
        assertThat(operations).extracting(op -> op.getClass().getSimpleName())
                .containsExactly("Remove", "Add");
    }

    // ---- helpers to edit a mutable copy --------------------------------------------------------

    private static String target(Operation op) {
        return switch (op) {
            case Operation.Set set -> set.target().toString();
            case Operation.Add add -> add.into().toString();
            case Operation.Remove remove -> remove.target().toString();
            case Operation.Order order -> order.target().toString();
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T mutable(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put((String) k, mutable(v)));
            return (T) copy;
        }
        if (node instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(e -> copy.add(mutable(e)));
            return (T) copy;
        }
        return (T) node;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        return (Map<String, Object>) node;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> node, String key) {
        return (List<Object>) node.get(key);
    }

    private static Map<String, Object> person(Map<String, Object> doc) {
        return asMap(doc.get("person"));
    }

    private static Map<String, Object> byId(List<Object> list, String id) {
        return list.stream().map(OverlayDiffTest::asMap).filter(m -> id.equals(m.get("id"))).findFirst().orElseThrow();
    }

    private static Map<String, Object> section(Map<String, Object> doc, String id) {
        return byId(list(doc, "sections"), id);
    }

    private static Map<String, Object> item(Map<String, Object> doc, String sectionId, String itemId) {
        return byId(list(section(doc, sectionId), "items"), itemId);
    }

    private static Map<String, Object> bullet(Map<String, Object> doc, String sectionId, String itemId, String id) {
        return byId(list(item(doc, sectionId, itemId), "bullets").stream().filter(b -> b instanceof Map).toList(), id);
    }
}
