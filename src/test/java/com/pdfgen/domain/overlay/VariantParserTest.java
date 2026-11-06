package com.pdfgen.domain.overlay;

import com.pdfgen.domain.overlay.Operation.ElementKind;
import com.pdfgen.domain.overlay.Operation.Placement;
import com.pdfgen.domain.tree.Tree;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VariantParserTest {

    private final VariantParser parser = new VariantParser();

    private Variant parse(Map<String, Object> raw) {
        return parser.parse("qa", Tree.freezeMap(raw));
    }

    private Variant parseOps(Map<String, Object>... operations) {
        return parse(Map.of("extends", "cv", "operations", List.of(operations)));
    }

    @Test
    void parsesAllOperationsInOrderWithOneBasedIndexes() {
        Variant variant = parseOps(
                Map.of("set", Map.of("target", "person", "fields", Map.of("jobTitle", "QA"))),
                Map.of("add", Map.of("into", "experience", "after", "acme", "item", Map.of("id", "x"))),
                Map.of("remove", Map.of("target", "experience/acme/b1")),
                Map.of("order", Map.of("ids", List.of("a", "b"))));

        assertThat(variant.baseId()).isEqualTo("cv");
        assertThat(variant.operations()).hasSize(4);
        assertThat(variant.operations().get(1)).isInstanceOfSatisfying(Operation.Add.class, add -> {
            assertThat(add.index()).isEqualTo(2);
            assertThat(add.kind()).isEqualTo(ElementKind.ITEM);
            assertThat(add.placement()).isEqualTo(Placement.after("acme"));
        });
        assertThat(variant.operations().get(2)).isInstanceOfSatisfying(Operation.Remove.class,
                remove -> assertThat(remove.target().segments()).containsExactly("experience", "acme", "b1"));
        assertThat(variant.operations().get(3)).isInstanceOfSatisfying(Operation.Order.class,
                order -> assertThat(order.target().isRoot()).isTrue());
    }

    @Test
    void requiresExtends() {
        assertThatThrownBy(() -> parse(Map.of("extends", "")))
                .isInstanceOf(OverlayException.class).hasMessageContaining("'extends'");
        assertThatThrownBy(() -> parse(Map.of("extends", "qa")))
                .hasMessageContaining("cannot extend itself");
    }

    @Test
    void rejectsUnknownRootKeysAndOperations() {
        assertThatThrownBy(() -> parse(Map.of("extends", "cv", "sections", List.of())))
                .hasMessageContaining("unknown key(s) [sections]");
        assertThatThrownBy(() -> parseOps(Map.of("rename", Map.of("target", "x"))))
                .hasMessageContaining("operation #1").hasMessageContaining("unknown operation 'rename'");
    }

    @Test
    void rejectsAmbiguousOrMalformedOperations() {
        assertThatThrownBy(() -> parseOps(Map.of("add", Map.of("after", "a", "before", "b", "item", Map.of()))))
                .hasMessageContaining("either 'after' or 'before'");
        assertThatThrownBy(() -> parseOps(Map.of("add", Map.of("item", Map.of(), "bullet", "x"))))
                .hasMessageContaining("exactly one of");
        assertThatThrownBy(() -> parseOps(Map.of("set", Map.of("target", "a", "fields", Map.of("id", "b")))))
                .hasMessageContaining("'id' cannot be changed");
        assertThatThrownBy(() -> parseOps(Map.of("remove", Map.of("target", "a//b"))))
                .hasMessageContaining("empty segment");
        assertThatThrownBy(() -> parseOps(Map.of("order", Map.of("ids", List.of()))))
                .hasMessageContaining("non-empty list");
        assertThatThrownBy(() -> parseOps(Map.of("set", Map.of("target", "a"), "remove", Map.of())))
                .hasMessageContaining("single key");
    }
}
