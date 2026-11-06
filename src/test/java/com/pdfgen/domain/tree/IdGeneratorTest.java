package com.pdfgen.domain.tree;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.pdfgen.domain.TestTrees.baseCv;
import static com.pdfgen.domain.TestTrees.items;
import static org.assertj.core.api.Assertions.assertThat;

class IdGeneratorTest {

    @Test
    void slugsAreAsciiLowercaseAndHyphenated() {
        assertThat(IdGenerator.slug("Ștefan cel Mare – Școala Ț", 10)).isEqualTo("stefan-cel-mare-scoala-t");
        assertThat(IdGenerator.slug("Developed and maintained features for a hybrid app", 4))
                .isEqualTo("developed-and-maintained-features");
        assertThat(IdGenerator.slug("   ;;; ", 4)).isEqualTo("item");
        assertThat(IdGenerator.slug("x".repeat(60), 1)).hasSize(40);
    }

    @Test
    void uniqueAddsASuffix() {
        assertThat(IdGenerator.unique("acme", Set.of())).isEqualTo("acme");
        assertThat(IdGenerator.unique("acme", Set.of("acme", "acme-2"))).isEqualTo("acme-3");
    }

    @Test
    void assignsIdsOnlyToPlainTextElementsDeterministically() {
        Map<String, Object> once = IdGenerator.assignMissingTextIds(baseCv());
        assertThat(IdGenerator.assignMissingTextIds(baseCv())).isEqualTo(once);
        assertThat(IdGenerator.assignMissingTextIds(once)).as("idempotent").isEqualTo(once);

        var acme = items(once, "experience").getFirst();
        assertThat((List<Object>) acme.get("bullets")).containsExactly(
                Map.of("id", "b1", "text", "First;"),
                Map.of("id", "second-plain-text", "text", "Second, plain text;"),
                Map.of("id", "b3", "text", "Third."));
        assertThat(items(once, "skills")).extracting(i -> i.get("id")).containsExactly("java", "sql");
    }
}
