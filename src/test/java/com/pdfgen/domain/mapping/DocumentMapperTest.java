package com.pdfgen.domain.mapping;

import com.pdfgen.domain.model.ContactItem;
import com.pdfgen.domain.model.ContactType;
import com.pdfgen.domain.model.Document;
import com.pdfgen.domain.model.SectionContent;
import com.pdfgen.domain.model.TextItem;
import com.pdfgen.domain.tree.Tree;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.pdfgen.domain.TestTrees.baseCv;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class DocumentMapperTest {

    private final DocumentMapper mapper = new DocumentMapper();

    private static Map<String, Object> withSections(Object... sections) {
        Map<String, Object> tree = new LinkedHashMap<>(baseCv());
        tree.put("sections", List.of(sections));
        return Tree.freezeMap(tree);
    }

    @Test
    void mapsTypedSectionsInOrder() {
        Document document = mapper.map("'cv'", baseCv());

        assertThat(document.person().name()).isEqualTo("Jane Doe");
        assertThat(document.meta().theme()).isEqualTo("classic");
        assertThat(document.sections()).extracting(s -> s.id() + ":" + s.kind())
                .containsExactly("summary:text", "experience:entries", "skills:list");

        var entries = (SectionContent.Entries) document.sections().get(1).content();
        assertThat(entries.entries().getFirst().bullets()).containsExactly(
                new TextItem(Optional.of("b1"), "First;"),
                new TextItem(Optional.empty(), "Second, plain text;"),
                new TextItem(Optional.of("b3"), "Third."));
    }

    @Test
    void mapsContactSectionWithDefaultsAndQr() {
        Document document = mapper.map("'cv'", withSections(Map.of("id", "contact", "kind", "contact",
                "title", "CONTACT", "qr", Map.of("url", "https://x.example"),
                "items", List.of(
                        Map.of("id", "mail", "type", "email", "value", "a@b.c", "column", "right", "bold", true),
                        Map.of("id", "gh", "type", "github", "value", "/me")))));

        var contact = (SectionContent.Contact) document.sections().getFirst().content();
        assertThat(contact.column(ContactItem.Column.RIGHT)).singleElement()
                .satisfies(item -> {
                    assertThat(item.type()).isEqualTo(ContactType.EMAIL);
                    assertThat(item.emphasized()).isTrue();
                });
        assertThat(contact.column(ContactItem.Column.LEFT)).extracting(ContactItem::id).containsExactly("gh");
        assertThat(contact.qr()).hasValueSatisfying(qr -> assertThat(qr.url()).isEqualTo("https://x.example"));
    }

    @Test
    void reportsAllProblemsAtOnceWithPaths() {
        Map<String, Object> broken = Tree.freezeMap(Map.of(
                "meta", Map.of("type", "letter"),
                "person", Map.of("jobTitle", "X"),
                "sections", List.of(
                        Map.of("id", "a", "kind", "list", "title", "A", "items", List.of("ok")),
                        Map.of("id", "a", "kind", "list", "title", "Dup", "items", List.of()),
                        Map.of("id", "b", "kind", "table", "title", "B", "items", List.of()),
                        Map.of("id", "c", "kind", "entries", "title", "C", "colour", "red",
                                "items", List.of(Map.of("id", "e1", "bullets", List.of(true)))))));

        var error = catchThrowableOfType(DocumentValidationException.class, () -> mapper.map("'broken'", broken));

        assertThat(error.getMessage()).startsWith("Invalid document 'broken'");
        assertThat(error.problems()).anySatisfy(p -> assertThat(p).contains("meta: unsupported type 'letter'"))
                .anySatisfy(p -> assertThat(p).isEqualTo("person.name: is required"))
                .anySatisfy(p -> assertThat(p).contains("duplicate id 'a'"))
                .anySatisfy(p -> assertThat(p).contains("unknown kind 'table'"))
                .anySatisfy(p -> assertThat(p).contains("sections[c]: unknown field(s) [colour]"))
                .anySatisfy(p -> assertThat(p).contains("sections[c].items[e1].heading: is required"))
                .anySatisfy(p -> assertThat(p).contains("bullets[0]: must be text or {id, text}"));
    }

    @Test
    void rejectsReservedSectionIdsAndBadIds() {
        assertThatThrownBy(() -> mapper.map("'x'", withSections(
                Map.of("id", "person", "kind", "list", "title", "P", "items", List.of()),
                Map.of("id", "has space", "kind", "list", "title", "S", "items", List.of()))))
                .isInstanceOf(DocumentValidationException.class)
                .hasMessageContaining("'person' is a reserved name")
                .hasMessageContaining("may only contain letters");
    }

    @Test
    void validatesCategoriesAndBooleans() {
        assertThatThrownBy(() -> mapper.map("'x'", withSections(
                Map.of("id", "s", "kind", "categories", "title", "S", "pageBreakBefore", "yes",
                        "items", List.of(Map.of("id", "c", "label", "L", "values", List.of("ok", "")))))))
                .hasMessageContaining("pageBreakBefore: must be true or false")
                .hasMessageContaining("values[1] must be non-empty text");
    }
}
