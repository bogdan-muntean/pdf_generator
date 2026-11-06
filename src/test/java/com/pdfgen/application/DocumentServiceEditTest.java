package com.pdfgen.application;

import com.pdfgen.adapters.render.ClasspathThemeCatalog;
import com.pdfgen.adapters.storage.FileDocumentRepository;
import com.pdfgen.domain.DocumentException;
import com.pdfgen.domain.mapping.DocumentValidationException;
import com.pdfgen.domain.model.Document;
import com.pdfgen.domain.tree.Tree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Editor use cases on a copy of samples/ (real files, fake renderer). */
class DocumentServiceEditTest {

    @TempDir
    Path data;

    private DocumentService service;
    private FileDocumentRepository repository;

    @BeforeEach
    void copySamples() throws IOException {
        for (String name : List.of("cv-sample.yaml", "cv-sample-qa.yaml")) {
            Files.copy(Path.of("samples", name), data.resolve(name));
        }
        repository = new FileDocumentRepository(data);
        PdfRenderer fake = (Document document) -> document.person().name().getBytes();
        service = new DocumentService(repository, fake, new ClasspathThemeCatalog());
    }

    @Test
    void savingAVariantWritesOnlyTheDifferencesAndReproducesTheEdit() throws IOException {
        var opened = service.openForEdit("cv-sample-qa");
        assertThat(opened.variant()).isTrue();
        assertThat(opened.parentTree()).isPresent();

        Map<String, Object> edited = mutable(opened.tree());
        person(edited).put("jobTitle", "TEST LEAD");
        var saved = service.save("cv-sample-qa", edited, opened.version());

        assertThat(person(saved.tree())).containsEntry("jobTitle", "TEST LEAD");
        assertThat(saved.tree()).isEqualTo(Tree.freezeMap(edited));
        String file = Files.readString(data.resolve("cv-sample-qa.yaml"));
        assertThat(file).contains("extends: cv-sample").contains("TEST LEAD").doesNotContain("Alex Popescu");
        assertThat(service.resolve("cv-sample-qa").document().person().jobTitle()).hasValue("TEST LEAD");
        assertThat(history("cv-sample-qa")).hasSize(1);
    }

    @Test
    void savingABaseGivesPlainBulletsIdsAndKeepsVariantsWorking() {
        var opened = service.openForEdit("cv-sample");
        Map<String, Object> edited = mutable(opened.tree());
        person(edited).put("name", "Alexandra Popescu");
        var saved = service.save("cv-sample", edited, opened.version());

        assertThat(saved.writtenByApp()).isTrue();
        assertThat(saved.warnings()).isEmpty();
        assertThat(service.resolve("cv-sample-qa").document().person().name()).isEqualTo("Alexandra Popescu");
        assertThat(saved.tree().toString()).contains("id=introduced-contract-tests-between");
    }

    @Test
    void warnsWhenABaseEditBreaksAVariant() {
        var opened = service.openForEdit("cv-sample");
        Map<String, Object> edited = mutable(opened.tree());
        sections(edited).removeIf(s -> "projects".equals(((Map<?, ?>) s).get("id")));
        var saved = service.save("cv-sample", edited, opened.version());
        assertThat(saved.warnings()).anySatisfy(w -> assertThat(w).contains("cv-sample-qa").contains("projects"));
    }

    @Test
    void rejectsStaleVersionsAndInvalidDocuments() {
        var opened = service.openForEdit("cv-sample");
        assertThatThrownBy(() -> service.save("cv-sample", opened.tree(), "stale"))
                .isInstanceOf(VersionConflictException.class);

        Map<String, Object> broken = mutable(opened.tree());
        person(broken).remove("name");
        assertThatThrownBy(() -> service.save("cv-sample", broken, opened.version()))
                .isInstanceOfSatisfying(DocumentValidationException.class, e ->
                        assertThat(e.details()).anySatisfy(p -> assertThat(p.path()).isEqualTo("person.name")));
    }

    @Test
    void createsOpensAndDeletesVariants() {
        service.createVariant("cv-sample", "cv-sample-java");
        var created = service.openForEdit("cv-sample-java");
        assertThat(created.tree()).isEqualTo(created.parentTree().orElseThrow());

        assertThatThrownBy(() -> service.createVariant("cv-sample", "cv-sample-java")).hasMessageContaining("exists");
        service.createVariant("cv-sample-qa", "cv-sample-qa-acme"); // a variant of a variant (ADR-0025)
        assertThatThrownBy(() -> service.delete("cv-sample-qa", false, java.util.Optional.empty())).hasMessageContaining("cv-sample-qa-acme extends it");
        service.delete("cv-sample-qa-acme", false, java.util.Optional.empty());
        assertThatThrownBy(() -> service.createVariant("cv-sample", "Bad Name")).hasMessageContaining("lowercase");

        service.delete("cv-sample-java", false, java.util.Optional.empty());
        assertThat(repository.exists("cv-sample-java")).isFalse();
        assertThat(history("cv-sample-java")).hasSize(1);
        assertThatThrownBy(() -> service.delete("cv-sample", false, java.util.Optional.empty())).isInstanceOf(DocumentException.class);
    }

    @Test
    void previewRendersADraftWithoutWritingAnything() throws IOException {
        String before = Files.readString(data.resolve("cv-sample.yaml"));
        Map<String, Object> draft = mutable(service.openForEdit("cv-sample").tree());
        person(draft).put("name", "Draft Name");
        assertThat(new String(service.preview(draft).content())).isEqualTo("Draft Name");
        assertThat(Files.readString(data.resolve("cv-sample.yaml"))).isEqualTo(before);
    }

    private List<Path> history(String id) {
        try (Stream<Path> files = Files.list(data.resolve(".history").resolve(id))) {
            return files.toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> person(Map<String, Object> tree) {
        return (Map<String, Object>) tree.get("person");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> sections(Map<String, Object> tree) {
        return (List<Object>) tree.get("sections");
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
}
