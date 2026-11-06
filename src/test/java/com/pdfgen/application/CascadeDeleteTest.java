package com.pdfgen.application;

import com.pdfgen.adapters.render.ClasspathThemeCatalog;
import com.pdfgen.adapters.storage.FileDocumentRepository;
import com.pdfgen.domain.DocumentException;
import com.pdfgen.domain.model.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Deleting a base or a variant together with every variant built on it (ADR-0029). */
class CascadeDeleteTest {

    @TempDir
    Path data;

    private DocumentService service;
    private FileDocumentRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        for (String name : List.of("cv-sample.yaml", "cv-sample-qa.yaml", "cv-sample-qa-acme.yaml",
                "cv-sample-ro.yaml", "cover-letter-sample.yaml", "cover-letter-sample-initech.yaml")) {
            Files.copy(Path.of("samples", name), data.resolve(name));
        }
        Files.createDirectories(data.resolve("assets"));
        Files.write(data.resolve("assets/me.jpg"), new byte[] {1, 2, 3});
        repository = new FileDocumentRepository(data);
        service = new DocumentService(repository, (Document d) -> new byte[0], new ClasspathThemeCatalog());
        service.createVariant("cv-sample", "cv-sample-java");
    }

    private List<String> ids() {
        return repository.listIds();
    }

    @Test
    void listsTheSubtreeDeepestFirst() {
        List<String> order = service.subtreeDeepestFirst("cv-sample");
        assertThat(order).containsExactlyInAnyOrder("cv-sample-qa-acme", "cv-sample-qa", "cv-sample-java",
                "cv-sample"); // cv-sample-ro is another base
        assertThat(order.indexOf("cv-sample-qa-acme")).isLessThan(order.indexOf("cv-sample-qa"));
        assertThat(order.getLast()).isEqualTo("cv-sample");
        assertThat(service.subtreeDeepestFirst("cv-sample-qa-acme")).containsExactly("cv-sample-qa-acme");
    }

    @Test
    void deletingABaseDeletesAllThreeLevelsAndNothingElse() {
        List<String> expected = service.subtreeDeepestFirst("cv-sample");
        DocumentService.Deleted deleted = service.delete("cv-sample", true, Optional.of(expected));

        assertThat(deleted.deleted()).isEqualTo(expected);
        assertThat(ids()).containsExactly("cover-letter-sample", "cover-letter-sample-initech", "cv-sample-ro");
        assertThat(data.resolve("assets/me.jpg")).exists();
        assertThat(data.resolve(deleted.safetyBackup())).isRegularFile();
    }

    @Test
    void deletingAMiddleVariantTakesItsChildrenOnly() {
        service.delete("cv-sample-qa", true, Optional.of(List.of("cv-sample-qa", "cv-sample-qa-acme")));
        assertThat(ids()).contains("cv-sample", "cv-sample-java", "cv-sample-ro").doesNotContain("cv-sample-qa",
                "cv-sample-qa-acme");
    }

    @Test
    void withoutCascadeADocumentWithVariantsIsRefusedButALoneBaseCanGo() {
        assertThatThrownBy(() -> service.delete("cv-sample-qa", false, Optional.empty()))
                .isInstanceOf(DocumentException.class).hasMessageContaining("cv-sample-qa-acme extends it");
        service.delete("cover-letter-sample-initech", false, Optional.empty());
        service.delete("cover-letter-sample", false, Optional.empty()); // a base without variants
        assertThat(ids()).doesNotContain("cover-letter-sample");
    }

    @Test
    void aStaleConfirmationDeletesNothing() {
        List<String> confirmed = service.subtreeDeepestFirst("cv-sample-qa");
        service.createVariant("cv-sample-qa-acme", "cv-sample-qa-acme-2"); // created after the confirmation
        assertThatThrownBy(() -> service.delete("cv-sample-qa", true, Optional.of(confirmed)))
                .isInstanceOf(StaleDeleteException.class).hasMessageContaining("cv-sample-qa-acme-2");
        assertThat(ids()).contains("cv-sample-qa", "cv-sample-qa-acme", "cv-sample-qa-acme-2");
    }

    @Test
    void anUnreadableFileBlocksTheDeleteBecauseItMightExtendTheDocument() throws IOException {
        Files.writeString(data.resolve("broken.yaml"), "extends: [unclosed");
        assertThatThrownBy(() -> service.delete("cv-sample-qa-acme", true, Optional.of(List.of("cv-sample-qa-acme"))))
                .isInstanceOf(DocumentException.class).hasMessageContaining("'broken' cannot be read");
        assertThat(ids()).contains("cv-sample-qa-acme");
        // the broken file itself can be deleted
        service.delete("broken", false, Optional.empty());
        assertThat(ids()).doesNotContain("broken");
    }

    @Test
    void unknownDocument() {
        assertThatThrownBy(() -> service.delete("nope", true, Optional.of(List.of("nope"))))
                .isInstanceOf(DocumentNotFoundException.class);
    }
}
