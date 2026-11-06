package com.pdfgen.domain.mapping;

import com.pdfgen.domain.model.Document;
import com.pdfgen.domain.model.SectionContent;
import com.pdfgen.domain.tree.Tree;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** The per-type rules of ADR-0026, as the mapper enforces them. */
class DocumentTypeRulesTest {

    private final DocumentMapper mapper = new DocumentMapper();

    private static Map<String, Object> letter(Map<String, Object> overrides) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("meta", Map.of("type", "cover-letter"));
        tree.put("person", Map.of("name", "Jane Doe"));
        tree.put("letter", Map.of("salutation", "Dear Hiring Manager,", "closing", "Sincerely,"));
        tree.put("sections", List.of(
                Map.of("id", "contact", "kind", "contact", "title", "Sender", "items", List.of(
                        Map.of("id", "email", "type", "email", "value", "jane@example.com"))),
                Map.of("id", "recipient", "kind", "lines", "title", "Recipient", "items", List.of("Acme", "Main St 1")),
                Map.of("id", "body", "kind", "text", "title", "Letter", "items", List.of("Hello."))));
        tree.putAll(overrides);
        return Tree.freezeMap(tree);
    }

    private List<Problem> problems(Map<String, Object> tree) {
        return catchThrowableOfType(DocumentValidationException.class, () -> mapper.map("'x'", tree)).details();
    }

    @Test
    void mapsACoverLetterWithItsLetterFieldsAndLines() {
        Document doc = mapper.map("'letter'", letter(Map.of()));
        assertThat(doc.letter()).hasValueSatisfying(l -> {
            assertThat(l.salutation()).isEqualTo("Dear Hiring Manager,");
            assertThat(l.signature()).isEmpty();
        });
        assertThat(doc.signature()).isEqualTo("Jane Doe");
        assertThat(doc.sections().get(1).content()).isInstanceOfSatisfying(SectionContent.Lines.class,
                lines -> assertThat(lines.lines()).extracting(l -> l.text()).containsExactly("Acme", "Main St 1"));
    }

    @Test
    void aCoverLetterNeedsSalutationAndClosingAndRejectsCvOnlyData() {
        Map<String, Object> qrContact = Map.of("id", "contact", "kind", "contact", "title", "Sender",
                "items", List.of(), "qr", Map.of("url", "https://x.example"));
        List<Problem> problems = problems(letter(Map.of(
                "person", Map.of("name", "Jane", "photo", "assets/p.jpg", "jobTitle", "DEV"),
                "letter", Map.of("salutation", "Hi,"),
                "sections", List.of(qrContact, Map.of("id", "contact2", "kind", "contact", "title", "Again", "items", List.of()),
                        Map.of("id", "jobs", "kind", "entries", "title", "JOBS", "items", List.of())))));
        assertThat(problems).extracting(Problem::path).contains("person.photo", "person.jobTitle", "letter.closing",
                "sections[contact].qr", "sections[jobs]", "sections");
        assertThat(problems).anyMatch(p -> p.message().contains("at most 1 contact section"));
        assertThat(problems(letter(Map.of("letter", "nope")))).extracting(Problem::path).contains("letter");
    }

    @Test
    void aCvRejectsTheLetterRootAndLines() {
        Map<String, Object> cv = new LinkedHashMap<>(letter(Map.of()));
        cv.put("meta", Map.of("type", "cv"));
        List<Problem> problems = problems(Tree.freezeMap(cv));
        assertThat(problems).anyMatch(p -> p.path().equals("letter") && p.message().contains("only used in cover letters"));
        assertThat(problems).anyMatch(p -> p.path().equals("sections[recipient]") && p.message().contains("not used in a CV"));
    }

    @Test
    void letterIsAReservedSectionId() {
        Map<String, Object> tree = letter(Map.of("sections", List.of(
                Map.of("id", "letter", "kind", "text", "title", "Body", "items", List.of("Hi.")))));
        assertThat(problems(tree)).anyMatch(p -> p.message().contains("reserved name"));
    }
}
