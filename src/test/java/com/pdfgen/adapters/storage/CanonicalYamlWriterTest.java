package com.pdfgen.adapters.storage;

import com.pdfgen.domain.tree.Tree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code load(write(tree)) == tree}: the app must never change data by saving it (ADR-0022). */
class CanonicalYamlWriterTest {

    private final CanonicalYamlWriter writer = new CanonicalYamlWriter();

    @TempDir
    Path dir;

    private Map<String, Object> roundTrip(Map<String, Object> tree) throws IOException {
        Files.writeString(dir.resolve("doc.yaml"), writer.write(tree));
        return new FileDocumentRepository(dir).loadTree("doc");
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain text", "a: b", "#hash", "x #y", "- dash", "yes", "No", "on", "null", "~",
            "1.0", "2023-07", "+40761341586", "0xFF", "true", ".5", "multi\nline", "multi\nline\n", " leading",
            "trailing ", "quote \"x\"", "back\\slash", "tab\tx", "[brackets]", "{braces}", "a, b", "Ș ț ă – —",
            "&anchor", "*alias", "!tag", "%percent", "@at", "`tick", "ends with:", "", "'single'", "?question",
            "|pipe", ">gt", "line sep", "C#", "C++", "Node.js", "https://x.example/a?b=c#d", "\n\nlead",
            "trail\n\n", "  \n  "})
    void anyStringSurvivesInBlockAndFlowPositions(String text) throws IOException {
        Map<String, Object> tree = Tree.freezeMap(Map.of(
                "block", text,
                "list", List.of(text, "other"),
                "flow", Map.of("id", "x", "value", text),
                "nested", Map.of("items", List.of(Map.of("id", "a", "text", text, "big", "x".repeat(120))))));
        assertThat(roundTrip(tree)).isEqualTo(tree);
    }

    @Test
    void samplesSurviveAndLookTidy() throws IOException {
        var repository = new FileDocumentRepository(Path.of("samples"));
        for (String id : repository.listIds()) {
            Map<String, Object> tree = repository.loadTree(id);
            assertThat(roundTrip(tree)).as(id).isEqualTo(tree);
        }
        String yaml = writer.write(repository.loadTree("cv-sample"));
        assertThat(yaml).startsWith(CanonicalYamlWriter.HEADER)
                .contains("  - {id: phone, type: phone, value: \"+40700000000\", bold: true}")
                .contains("      - {id: backend, label: Back-end, values: [Java, Spring Boot, Node.js, REST APIs]}")
                .contains("    title: WORK EXPERIENCE");
    }

    @Test
    void booleansEmptyCollectionsAndOddKeys() throws IOException {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("flag", true);
        tree.put("empty", List.of());
        tree.put("nothing", Map.of());
        tree.put("odd key", "v");
        assertThat(roundTrip(Tree.freezeMap(tree))).isEqualTo(Tree.freezeMap(tree));
    }
}
