package com.pdfgen.domain.mapping;

import com.pdfgen.domain.model.Category;
import com.pdfgen.domain.model.ContactItem;
import com.pdfgen.domain.model.ContactType;
import com.pdfgen.domain.model.Document;
import com.pdfgen.domain.model.DocumentType;
import com.pdfgen.domain.model.DocumentTypeRules;
import com.pdfgen.domain.model.Letter;
import com.pdfgen.domain.model.Entry;
import com.pdfgen.domain.model.Meta;
import com.pdfgen.domain.model.Person;
import com.pdfgen.domain.model.QrCode;
import com.pdfgen.domain.model.Section;
import com.pdfgen.domain.model.SectionContent;
import com.pdfgen.domain.model.TextItem;
import com.pdfgen.domain.overlay.OverlayEngine;
import com.pdfgen.domain.tree.Tree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Maps a raw document tree to the typed {@link Document} and validates it (ADR-0013).
 * All problems are collected and reported together in one {@link DocumentValidationException}.
 */
public final class DocumentMapper {

    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");
    private static final List<String> KINDS = List.of("contact", "text", "entries", "categories", "list", "lines");

    /**
     * @param source human-readable origin for error messages, e.g. {@code "'cv-en'"} or
     *               {@code "'cv-en' + variant 'cv-qa'"}
     */
    public Document map(String source, Map<String, Object> tree) {
        List<Problem> problems = new ArrayList<>();
        Fields root = new Fields(tree, "", problems, Set.of("meta", "person", "sections", OverlayEngine.LETTER));

        Meta meta = mapMeta(new Fields(root.requiredMap("meta"), "meta", problems,
                Set.of("type", "lang", "theme", "outputName")));
        DocumentTypeRules rules = DocumentTypeRules.of(meta.type());
        Map<String, Object> rawPerson = root.requiredMap("person");
        for (String key : List.of("jobTitle", "photo")) {
            if (!rules.personKeys().contains(key) && rawPerson.containsKey(key)) {
                problems.add(new Problem("person." + key, "is not used in a " + label(meta.type()) + "; remove it"));
            }
        }
        Person person = mapPerson(new Fields(rawPerson, "person", problems, Set.of("name", "jobTitle", "photo")));
        Optional<Letter> letter = mapLetter(root, rules, meta.type(), problems);
        List<Section> sections = mapSections(root, rules, meta.type(), problems);

        if (!problems.isEmpty()) {
            throw new DocumentValidationException(source, problems);
        }
        return new Document(meta, person, sections, letter);
    }

    private static String label(DocumentType type) {
        return type == DocumentType.CV ? "CV" : "cover letter";
    }

    /** The {@code letter} root: required for cover letters, rejected elsewhere (ADR-0026). */
    private Optional<Letter> mapLetter(Fields root, DocumentTypeRules rules, DocumentType type,
                                       List<Problem> problems) {
        if (!rules.letter()) {
            root.optionalMap(OverlayEngine.LETTER).ifPresent(ignored -> problems.add(new Problem(OverlayEngine.LETTER,
                    "is only used in cover letters, not in a " + label(type))));
            return Optional.empty();
        }
        Fields fields = new Fields(root.requiredMap(OverlayEngine.LETTER), OverlayEngine.LETTER, problems,
                Set.of("salutation", "closing", "signature", "date", "subject"));
        return Optional.of(new Letter(fields.requiredText("salutation"), fields.requiredText("closing"),
                fields.optionalText("signature"), fields.optionalText("date"), fields.optionalText("subject")));
    }

    private Meta mapMeta(Fields fields) {
        String typeKey = fields.requiredText("type");
        Optional<DocumentType> type = DocumentType.fromKey(typeKey);
        if (type.isEmpty() && !typeKey.isEmpty()) {
            fields.problem("unsupported type '" + typeKey + "'; supported: "
                    + Arrays.stream(DocumentType.values()).map(DocumentType::key).toList());
        }
        return new Meta(type.orElse(DocumentType.CV),
                fields.optionalText("lang").orElse("en"),
                fields.optionalText("theme").orElse("classic"),
                fields.optionalText("outputName"));
    }

    private Person mapPerson(Fields fields) {
        return new Person(fields.requiredText("name"), fields.optionalText("jobTitle"), fields.optionalText("photo"));
    }

    private List<Section> mapSections(Fields root, DocumentTypeRules rules, DocumentType type, List<Problem> problems) {
        List<Section> sections = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Object> rawSections = root.list("sections", true);
        for (int i = 0; i < rawSections.size(); i++) {
            Object raw = rawSections.get(i);
            String label = "sections[" + Tree.idOf(raw).orElse(String.valueOf(i)) + "]";
            if (!(raw instanceof Map<?, ?>)) {
                problems.add(new Problem(label, "must be a map with id, kind, title"));
                continue;
            }
            mapSection(asMap(raw), label, problems, seen, rules, type).ifPresent(sections::add);
        }
        long contacts = sections.stream().filter(s -> s.content() instanceof SectionContent.Contact).count();
        if (contacts > rules.maxContact()) {
            problems.add(new Problem("sections", "a " + label(type) + " has at most " + rules.maxContact()
                    + " contact section" + (rules.maxContact() == 1 ? "" : "s") + ", found " + contacts));
        }
        return sections;
    }

    private Optional<Section> mapSection(Map<String, Object> raw, String path, List<Problem> problems,
                                         Set<String> seenIds, DocumentTypeRules rules, DocumentType type) {
        String kind = raw.get("kind") instanceof String k ? k : "";
        Set<String> allowed = new HashSet<>(Set.of("id", "kind", "title", "pageBreakBefore", "items"));
        if (kind.equals("contact")) {
            allowed.add("qr");
        }
        Fields fields = new Fields(raw, path, problems, allowed);
        String id = requireId(fields, seenIds);
        if (OverlayEngine.RESERVED_ROOTS.contains(id)) {
            fields.problem("'" + id + "' is a reserved name and cannot be a section id");
        }
        String title = fields.requiredText("title");
        boolean pageBreak = fields.flag("pageBreakBefore", false);
        fields.requiredText("kind");
        if (!kind.isEmpty() && !KINDS.contains(kind)) {
            fields.problem("unknown kind '" + kind + "'; supported: " + KINDS);
            return Optional.empty();
        }
        if (!kind.isEmpty() && !rules.sectionKinds().contains(kind)) {
            fields.problem("kind '" + kind + "' is not used in a " + label(type) + "; allowed: " + rules.sectionKinds());
            return Optional.empty();
        }
        if (kind.equals("contact") && !rules.qrCode() && raw.containsKey("qr")) {
            problems.add(new Problem(path + ".qr", "a " + label(type) + " has no QR code; remove it"));
        }
        List<Object> items = fields.list("items", true);
        SectionContent content = switch (kind) {
            case "contact" -> mapContact(fields, items, problems);
            case "text" -> new SectionContent.Text(mapTextItems(fields.child("items"), items, problems));
            case "entries" -> new SectionContent.Entries(mapEach(fields.child("items"), items, problems,
                    (item, seen) -> mapEntry(item, seen, problems), ENTRY_KEYS));
            case "categories" -> new SectionContent.Categories(mapEach(fields.child("items"), items, problems,
                    this::mapCategory, CATEGORY_KEYS));
            case "list" -> new SectionContent.BulletList(mapTextItems(fields.child("items"), items, problems));
            case "lines" -> new SectionContent.Lines(mapTextItems(fields.child("items"), items, problems));
            default -> null;
        };
        return content == null ? Optional.empty() : Optional.of(new Section(id, title, pageBreak, content));
    }

    // ---- kinds ---------------------------------------------------------------------------------

    private SectionContent mapContact(Fields section, List<Object> items, List<Problem> problems) {
        List<ContactItem> contactItems = mapEach(section.child("items"), items, problems, (fields, seen) -> {
            String typeKey = fields.requiredText("type");
            Optional<ContactType> type = ContactType.fromKey(typeKey);
            if (type.isEmpty() && !typeKey.isEmpty()) {
                fields.problem("unknown contact type '" + typeKey + "'; supported: "
                        + Arrays.stream(ContactType.values()).map(ContactType::key).toList());
            }
            String columnKey = fields.optionalText("column").orElse("left");
            ContactItem.Column column = switch (columnKey) {
                case "left" -> ContactItem.Column.LEFT;
                case "right" -> ContactItem.Column.RIGHT;
                default -> {
                    fields.problem("column must be 'left' or 'right', found '" + columnKey + "'");
                    yield ContactItem.Column.LEFT;
                }
            };
            return new ContactItem(requireId(fields, seen), type.orElse(ContactType.OTHER),
                    fields.optionalText("label"), fields.requiredText("value"), fields.optionalText("href"),
                    fields.flag("bold", false), column);
        }, Set.of("id", "type", "label", "value", "href", "bold", "column"));

        Optional<QrCode> qr = section.optionalMap("qr").map(rawQr -> {
            Fields fields = new Fields(rawQr, section.child("qr"), problems, Set.of("url", "caption"));
            return new QrCode(fields.requiredText("url"), fields.optionalText("caption"));
        });
        return new SectionContent.Contact(contactItems, qr);
    }

    private Entry mapEntry(Fields fields, Set<String> seen, List<Problem> problems) {
        String id = requireId(fields, seen);
        List<TextItem> bullets = mapTextItems(fields.child("bullets"), fields.list("bullets", false), problems);
        return new Entry(id, fields.requiredText("heading"), fields.optionalText("subheading"),
                fields.optionalText("dates"), bullets);
    }

    private Category mapCategory(Fields fields, Set<String> seen) {
        String id = requireId(fields, seen);
        String label = fields.requiredText("label");
        List<String> values = new ArrayList<>();
        List<Object> rawValues = fields.list("values", true);
        for (int i = 0; i < rawValues.size(); i++) {
            if (rawValues.get(i) instanceof String value && !value.isBlank()) {
                values.add(value.strip());
            } else {
                fields.problem("values[" + i + "] must be non-empty text");
            }
        }
        return new Category(id, label, values);
    }

    // ---- shared helpers ------------------------------------------------------------------------

    private static final Set<String> ENTRY_KEYS = Set.of("id", "heading", "subheading", "dates", "bullets");
    private static final Set<String> CATEGORY_KEYS = Set.of("id", "label", "values");

    @FunctionalInterface
    private interface ItemMapper<T> {
        T map(Fields fields, Set<String> seenIds);
    }

    /** Maps a list of map-shaped items; ids must be unique within the list. */
    private <T> List<T> mapEach(String path, List<Object> rawItems, List<Problem> problems, ItemMapper<T> mapper,
                                Set<String> allowedKeys) {
        List<T> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rawItems.size(); i++) {
            Object raw = rawItems.get(i);
            String label = path + "[" + Tree.idOf(raw).orElse(String.valueOf(i)) + "]";
            if (!(raw instanceof Map<?, ?>)) {
                problems.add(new Problem(label, "must be a map with an id"));
                continue;
            }
            result.add(mapper.map(new Fields(asMap(raw), label, problems, allowedKeys), seen));
        }
        return result;
    }

    /** Plain text or {@code {id, text}} (ADR-0014). */
    private List<TextItem> mapTextItems(String path, List<Object> rawItems, List<Problem> problems) {
        List<TextItem> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rawItems.size(); i++) {
            Object raw = rawItems.get(i);
            if (raw instanceof String text) {
                if (text.isBlank()) {
                    problems.add(new Problem(path + "[" + i + "]", "must not be empty"));
                } else {
                    result.add(new TextItem(Optional.empty(), text.strip()));
                }
            } else if (raw instanceof Map<?, ?> map) {
                String label = path + "[" + Tree.idOf(raw).orElse(String.valueOf(i)) + "]";
                Fields fields = new Fields(asMap(map), label, problems, Set.of("id", "text"));
                String id = requireId(fields, seen);
                result.add(new TextItem(Optional.of(id), fields.requiredText("text")));
            } else {
                problems.add(new Problem(path + "[" + i + "]", "must be text or {id, text}, found " + Fields.describe(raw)));
            }
        }
        return result;
    }

    private static String requireId(Fields fields, Set<String> seenIds) {
        String id = fields.requiredText("id");
        if (!id.isEmpty()) {
            if (!ID_PATTERN.matcher(id).matches()) {
                fields.problem("id '" + id + "' may only contain letters, digits, '-' and '_'");
            }
            if (!seenIds.add(id)) {
                fields.problem("duplicate id '" + id + "'");
            }
        }
        return id;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return (Map<String, Object>) raw;
    }
}
