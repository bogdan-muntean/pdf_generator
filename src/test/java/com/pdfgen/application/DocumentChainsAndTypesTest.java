package com.pdfgen.application;

import com.pdfgen.adapters.render.ClasspathThemeCatalog;
import com.pdfgen.adapters.storage.FileDocumentRepository;
import com.pdfgen.domain.DocumentException;
import com.pdfgen.domain.mapping.DocumentMapper;
import com.pdfgen.domain.model.Document;
import com.pdfgen.domain.model.DocumentType;
import com.pdfgen.domain.tree.StarterDocuments;
import com.pdfgen.domain.tree.Tree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Variant chains (ADR-0025), new documents (ADR-0024) and cover letters (ADR-0026) on a copy of samples/. */
@SuppressWarnings("unchecked")
class DocumentChainsAndTypesTest {

    @TempDir
    Path data;

    private DocumentService service;

    @BeforeEach
    void copySamples() throws IOException {
        try (var files = Files.list(Path.of("samples"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".yaml")).toList()) {
                Files.copy(file, data.resolve(file.getFileName()));
            }
        }
        service = new DocumentService(new FileDocumentRepository(data), (Document d) -> new byte[0],
                new ClasspathThemeCatalog());
    }

    private void write(String id, String yaml) throws IOException {
        Files.writeString(data.resolve(id + ".yaml"), yaml);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutable(Map<String, Object> tree) {
        return (Map<String, Object>) deepCopy(tree);
    }

    private static Object deepCopy(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put((String) k, deepCopy(v)));
            return copy;
        }
        if (node instanceof List<?> list) {
            return new java.util.ArrayList<>(list.stream().map(DocumentChainsAndTypesTest::deepCopy).toList());
        }
        return node;
    }

    // ---- chains --------------------------------------------------------------------------------

    @Test
    void resolvesAThirdLevelOnTopOfItsResolvedParent() {
        Document acme = service.resolve("cv-sample-qa-acme").document();
        assertThat(acme.person().jobTitle()).contains("QA ENGINEER FOR ACME PAYMENTS"); // overrides the parent
        assertThat(acme.sections()).extracting(s -> s.title()).contains("WHY ACME", "PROFESSIONAL EXPERIENCE");
        assertThat(acme.meta().outputName()).contains("CV_Alex_Popescu_QA_Acme");

        var opened = service.openForEdit("cv-sample-qa-acme");
        assertThat(opened.parentId()).contains("cv-sample-qa");
        assertThat(opened.parentTree().orElseThrow()).isEqualTo(service.openForEdit("cv-sample-qa").tree());
        assertThat(opened.version().split(":")).hasSize(3);
    }

    @Test
    void aChangeInTheBaseFlowsDownToTheThirdLevel() {
        var base = service.openForEdit("cv-sample");
        Map<String, Object> edited = mutable(base.tree());
        ((Map<String, Object>) edited.get("person")).put("name", "Alexandra Popescu");
        service.save("cv-sample", edited, base.version());

        assertThat(service.resolve("cv-sample-qa-acme").document().person().name()).isEqualTo("Alexandra Popescu");
    }

    @Test
    void savingTheThirdLevelStoresOnlyItsDifferencesFromTheParent() throws IOException {
        var opened = service.openForEdit("cv-sample-qa-acme");
        Map<String, Object> edited = mutable(opened.tree());
        ((Map<String, Object>) edited.get("person")).put("jobTitle", "TEST LEAD");
        var saved = service.save("cv-sample-qa-acme", edited, opened.version());

        assertThat(saved.tree()).isEqualTo(Tree.freezeMap(edited)); // apply(parent, diff(parent, edited)) == edited
        String file = Files.readString(data.resolve("cv-sample-qa-acme.yaml"));
        assertThat(file).contains("extends: cv-sample-qa").contains("TEST LEAD").doesNotContain("REST Assured");
    }

    @Test
    void warnsWhenABaseEditBreaksAGrandchild() {
        var base = service.openForEdit("cv-sample");
        Map<String, Object> edited = mutable(base.tree());
        ((List<Object>) edited.get("sections")).removeIf(s -> "summary".equals(((Map<?, ?>) s).get("id")));
        var saved = service.save("cv-sample", edited, base.version());
        assertThat(saved.warnings()).anyMatch(w -> w.contains("'cv-sample-qa-acme' no longer applies"));
    }

    @Test
    void reportsCyclesMissingParentsAndTooDeepChains() throws IOException {
        write("loop-a", "extends: loop-b\n");
        write("loop-b", "extends: loop-a\n");
        assertThatThrownBy(() -> service.resolve("loop-a"))
                .hasMessage("Variant chain has a cycle: loop-a -> loop-b -> loop-a");

        write("orphan", "extends: gone\n");
        assertThatThrownBy(() -> service.resolve("orphan")).hasMessage("'orphan' extends 'gone', which does not exist");

        String parent = "cv-sample";
        for (int level = 1; level <= DocumentService.MAX_VARIANT_DEPTH; level++) {
            service.createVariant(parent, "deep-" + level);
            parent = "deep-" + level;
        }
        assertThat(service.resolve("deep-5").document().person().name()).isEqualTo("Alex Popescu");
        assertThatThrownBy(() -> service.createVariant("deep-5", "deep-6")).hasMessageContaining("at most 5");
        write("deep-6", "extends: deep-5\n");
        assertThatThrownBy(() -> service.resolve("deep-6")).hasMessageContaining("more than 5 variant levels");

        assertThat(service.list()).filteredOn(d -> d.id().equals("orphan")).singleElement()
                .satisfies(d -> assertThat(d.loadError()).hasValueSatisfying(e -> assertThat(e).contains("gone")));
    }

    @Test
    void listsTheTypeOfEveryDocumentFromItsBase() {
        assertThat(service.list()).filteredOn(d -> d.id().equals("cv-sample-qa-acme")).singleElement()
                .satisfies(d -> {
                    assertThat(d.type()).contains(DocumentType.CV);
                    assertThat(d.parentId()).contains("cv-sample-qa");
                });
        assertThat(service.list()).filteredOn(d -> d.id().equals("cover-letter-sample-initech")).singleElement()
                .satisfies(d -> assertThat(d.type()).contains(DocumentType.COVER_LETTER));
    }

    // ---- new documents -------------------------------------------------------------------------

    @Test
    void createsACvAndACoverLetterFromScratch() {
        service.createDocument("cv-de", DocumentType.CV);
        service.createDocument("letter-de", DocumentType.COVER_LETTER);

        Document cv = service.resolve("cv-de").document();
        assertThat(cv.sections()).extracting(s -> s.kind())
                .containsExactly("contact", "text", "entries", "entries", "categories", "categories");
        assertThat(service.render("cv-de").fileName()).isEqualTo("cv-de.pdf");

        Document letter = service.resolve("letter-de").document();
        assertThat(letter.letter()).hasValueSatisfying(l -> assertThat(l.closing()).isEqualTo("Sincerely,"));
        assertThat(letter.signature()).isEqualTo("Your Name");
        assertThat(letter.sections()).extracting(s -> s.kind()).containsExactly("contact", "lines", "text");
        assertThat(service.rules(service.openForEdit("letter-de")).sectionKinds()).containsExactly("contact", "lines", "text");

        assertThatThrownBy(() -> service.createDocument("cv-de", DocumentType.CV)).hasMessageContaining("already exists");
        assertThatThrownBy(() -> service.createDocument("CV DE", DocumentType.CV)).hasMessageContaining("lowercase");
    }

    @Test
    void bothStartersAreValidDocuments() {
        new DocumentMapper().map("cv starter", StarterDocuments.cv());
        new DocumentMapper().map("letter starter", StarterDocuments.coverLetter());
    }

    // ---- cover letter variants -----------------------------------------------------------------

    @Test
    void aLetterVariantChangesLetterFieldsAndCanUnsetThem() throws IOException {
        Document initech = service.resolve("cover-letter-sample-initech").document();
        assertThat(initech.letter().orElseThrow().subject()).contains("Application for the Java Developer position");

        var opened = service.openForEdit("cover-letter-sample-initech");
        Map<String, Object> edited = mutable(opened.tree());
        ((Map<String, Object>) edited.get("letter")).remove("date");
        ((Map<String, Object>) edited.get("letter")).put("closing", "Kind regards,");
        var saved = service.save("cover-letter-sample-initech", edited, opened.version());
        assertThat(saved.tree()).isEqualTo(Tree.freezeMap(edited));

        Document resolved = service.resolve("cover-letter-sample-initech").document();
        assertThat(resolved.letter().orElseThrow().date()).isEmpty();
        assertThat(resolved.letter().orElseThrow().closing()).isEqualTo("Kind regards,");
        assertThat(Files.readString(data.resolve("cover-letter-sample-initech.yaml"))).contains("target: letter");
    }

    @Test
    void aVariantCannotTurnACvIntoALetter() throws IOException {
        write("cv-with-letter", """
                extends: cv-sample
                operations:
                  - set: { target: letter, fields: { salutation: "Hi," } }
                """);
        assertThatThrownBy(() -> service.resolve("cv-with-letter"))
                .isInstanceOf(DocumentException.class).hasMessageContaining("only used in cover letters");
    }
}
