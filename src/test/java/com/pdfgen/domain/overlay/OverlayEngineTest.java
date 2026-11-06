package com.pdfgen.domain.overlay;

import com.pdfgen.domain.tree.Tree;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.pdfgen.domain.TestTrees.baseCv;
import static com.pdfgen.domain.TestTrees.items;
import static com.pdfgen.domain.TestTrees.section;
import static com.pdfgen.domain.TestTrees.sectionIds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OverlayEngineTest {

    private final OverlayEngine engine = new OverlayEngine();
    private final VariantParser parser = new VariantParser();

    /** Applies a variant written as raw operations (the same shape as the YAML file). */
    private OverlayResult apply(Map<String, Object> base, Map<String, Object>... operations) {
        Map<String, Object> variant = Tree.freezeMap(Map.of("extends", "base", "operations", List.of(operations)));
        return engine.apply(base, parser.parse("variant", variant));
    }

    private Map<String, Object> merged(Map<String, Object>... operations) {
        return apply(baseCv(), operations).document();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> bullets(Map<String, Object> tree, String sectionId, String itemId) {
        return (List<Object>) items(tree, sectionId).stream()
                .filter(i -> itemId.equals(i.get("id"))).findFirst().orElseThrow().get("bullets");
    }

    @Nested
    class Set {
        @Test
        void renamesSectionTitle() {
            var tree = merged(Map.of("set", Map.of("target", "experience",
                    "fields", Map.of("title", "PROFESSIONAL EXPERIENCE"))));
            assertThat(section(tree, "experience").get("title")).isEqualTo("PROFESSIONAL EXPERIENCE");
        }

        @Test
        void changesItemFieldAndKeepsTheOthers() {
            var tree = merged(Map.of("set", Map.of("target", "experience/acme",
                    "fields", Map.of("heading", "Acme – Senior Dev"))));
            var acme = items(tree, "experience").getFirst();
            assertThat(acme.get("heading")).isEqualTo("Acme – Senior Dev");
            assertThat(acme.get("bullets")).asList().hasSize(3);
        }

        @Test
        void rewordsBulletWithId() {
            var tree = merged(Map.of("set", Map.of("target", "experience/acme/b3",
                    "fields", Map.of("text", "Reworded."))));
            assertThat(bullets(tree, "experience", "acme").get(2)).isEqualTo(Map.of("id", "b3", "text", "Reworded."));
        }

        @Test
        void replacesWholeBulletList() {
            var tree = merged(Map.of("set", Map.of("target", "experience/acme",
                    "fields", Map.of("bullets", List.of("Just one.")))));
            assertThat(bullets(tree, "experience", "acme")).containsExactly("Just one.");
        }

        @Test
        void overridesPersonField() {
            var tree = merged(Map.of("set", Map.of("target", "person", "fields", Map.of("jobTitle", "QA ENGINEER"))));
            assertThat(tree.get("person")).isEqualTo(Map.of("name", "Jane Doe", "jobTitle", "QA ENGINEER"));
        }

        @Test
        void overridesAllowedMetaFieldsOnly() {
            var tree = merged(Map.of("set", Map.of("target", "meta", "fields", Map.of("outputName", "CV_QA"))));
            assertThat(((Map<?, ?>) tree.get("meta")).get("outputName")).isEqualTo("CV_QA");

            assertThatThrownBy(() -> merged(Map.of("set", Map.of("target", "meta", "fields", Map.of("type", "x")))))
                    .isInstanceOf(OverlayException.class)
                    .hasMessageContaining("[type] cannot be changed");
        }

        @Test
        void metaHeaderIsAppliedBeforeOperations() {
            Map<String, Object> variant = Tree.freezeMap(Map.of("extends", "base",
                    "meta", Map.of("theme", "modern")));
            var tree = engine.apply(baseCv(), parser.parse("v", variant)).document();
            assertThat(((Map<?, ?>) tree.get("meta")).get("theme")).isEqualTo("modern");
        }
    }

    @Nested
    class Add {
        @Test
        void addsItemAfterAnchor() {
            var tree = merged(Map.of("add", Map.of("into", "experience", "after", "acme",
                    "item", Map.of("id", "new", "heading", "New – Job"))));
            assertThat(items(tree, "experience")).extracting(i -> i.get("id")).containsExactly("acme", "new", "globex");
        }

        @Test
        void addsItemBeforeAnchorAndAtTheEnd() {
            var tree = merged(
                    Map.of("add", Map.of("into", "experience", "before", "acme",
                            "item", Map.of("id", "first", "heading", "F"))),
                    Map.of("add", Map.of("into", "experience", "item", Map.of("id", "last", "heading", "L"))));
            assertThat(items(tree, "experience")).extracting(i -> i.get("id"))
                    .containsExactly("first", "acme", "globex", "last");
        }

        @Test
        void addsPlainTextBullet() {
            var tree = merged(Map.of("add", Map.of("into", "experience/acme", "after", "b1",
                    "bullet", "Inserted;")));
            assertThat(bullets(tree, "experience", "acme")).element(1).isEqualTo("Inserted;");
        }

        @Test
        void addsSection() {
            var tree = merged(Map.of("add", Map.of("after", "summary",
                    "section", Map.of("id", "hobbies", "kind", "list", "title", "HOBBIES", "items", List.of("Chess.")))));
            assertThat(sectionIds(tree)).containsExactly("summary", "hobbies", "experience", "skills");
        }

        @Test
        void rejectsDuplicateId() {
            assertThatThrownBy(() -> merged(Map.of("add", Map.of("into", "experience",
                    "item", Map.of("id", "acme", "heading", "Dup")))))
                    .isInstanceOf(OverlayException.class)
                    .hasMessageContaining("Variant 'variant', operation #1 (add)")
                    .hasMessageContaining("id 'acme' already exists");
        }

        @Test
        void rejectsElementKindAtWrongDepth() {
            assertThatThrownBy(() -> merged(Map.of("add", Map.of("into", "experience/acme",
                    "item", Map.of("id", "x", "heading", "X")))))
                    .isInstanceOf(OverlayException.class)
                    .hasMessageContaining("'item' must be added into a section");
        }

        @Test
        void rejectsItemWithoutId() {
            assertThatThrownBy(() -> merged(Map.of("add", Map.of("into", "experience",
                    "item", Map.of("heading", "X")))))
                    .hasMessageContaining("must be a map with an 'id'");
        }

        @Test
        void rejectsUnknownAnchor() {
            assertThatThrownBy(() -> merged(Map.of("add", Map.of("into", "experience", "after", "nope",
                    "item", Map.of("id", "x", "heading", "X")))))
                    .hasMessageContaining("no element with id 'nope' in 'experience'")
                    .hasMessageContaining("Known ids: [acme, globex]");
        }
    }

    @Nested
    class Remove {
        @Test
        void removesSectionItemAndBullet() {
            var tree = merged(
                    Map.of("remove", Map.of("target", "skills")),
                    Map.of("remove", Map.of("target", "experience/globex")),
                    Map.of("remove", Map.of("target", "experience/acme/b1")));
            assertThat(sectionIds(tree)).containsExactly("summary", "experience");
            assertThat(items(tree, "experience")).hasSize(1);
            assertThat(bullets(tree, "experience", "acme")).hasSize(2).doesNotContain(Map.of("id", "b1", "text", "First;"));
        }

        @Test
        void explainsThatPlainBulletsNeedAnId() {
            assertThatThrownBy(() -> merged(Map.of("remove", Map.of("target", "experience/acme/b2"))))
                    .hasMessageContaining("Known ids: [b1, b3]")
                    .hasMessageContaining("need an id in the base");
        }

        @Test
        void rejectsReservedRoots() {
            assertThatThrownBy(() -> merged(Map.of("remove", Map.of("target", "person"))))
                    .hasMessageContaining("'person' only supports 'set'");
        }
    }

    @Nested
    class Order {
        @Test
        void reordersListedIdsAndWarnsAboutTheRest() {
            OverlayResult result = apply(baseCv(), Map.of("order", Map.of("ids", List.of("skills", "summary"))));
            assertThat(sectionIds(result.document())).containsExactly("skills", "summary", "experience");
            assertThat(result.warnings()).singleElement().asString().contains("[experience]");
        }

        @Test
        void completeOrderHasNoWarning() {
            OverlayResult result = apply(baseCv(), Map.of("order", Map.of("target", "experience",
                    "ids", List.of("globex", "acme"))));
            assertThat(items(result.document(), "experience")).extracting(i -> i.get("id"))
                    .containsExactly("globex", "acme");
            assertThat(result.warnings()).isEmpty();
        }

        @Test
        void ordersBulletsKeepingPlainTextAfterListedOnes() {
            var tree = merged(Map.of("order", Map.of("target", "experience/acme", "ids", List.of("b3", "b1"))));
            assertThat(bullets(tree, "experience", "acme")).extracting(b -> Tree.idOf(b).orElse("plain"))
                    .containsExactly("b3", "b1", "plain");
        }

        @Test
        void rejectsUnknownAndDuplicateIds() {
            assertThatThrownBy(() -> merged(Map.of("order", Map.of("ids", List.of("summary", "ghost")))))
                    .hasMessageContaining("no element with id 'ghost'");
            assertThatThrownBy(() -> merged(Map.of("order", Map.of("ids", List.of("summary", "summary")))))
                    .hasMessageContaining("duplicates");
        }
    }

    @Test
    void neverMutatesTheBase() {
        Map<String, Object> base = baseCv();
        String before = base.toString();
        apply(base,
                Map.of("set", Map.of("target", "summary", "fields", Map.of("title", "X"))),
                Map.of("remove", Map.of("target", "experience/acme/b1")),
                Map.of("order", Map.of("ids", List.of("skills"))));
        assertThat(base.toString()).isEqualTo(before);
    }

    @Test
    void resultIsImmutable() {
        var tree = merged(Map.of("set", Map.of("target", "summary", "fields", Map.of("title", "X"))));
        assertThatThrownBy(() -> tree.put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> items(tree, "experience").clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
