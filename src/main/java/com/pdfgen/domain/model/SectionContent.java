package com.pdfgen.domain.model;

import java.util.List;
import java.util.Optional;

/**
 * The typed body of a section. Adding a kind = one record here + one mapper branch + one template fragment.
 */
public sealed interface SectionContent {

    String kind();

    /** CONTACT INFO: icons + values in two columns, optionally a QR code. */
    record Contact(List<ContactItem> items, Optional<QrCode> qr) implements SectionContent {
        public Contact {
            items = List.copyOf(items);
        }

        @Override
        public String kind() {
            return "contact";
        }

        public List<ContactItem> column(ContactItem.Column column) {
            return items.stream().filter(item -> item.column() == column).toList();
        }
    }

    /** EXECUTIVE SUMMARY: free paragraphs. */
    record Text(List<TextItem> paragraphs) implements SectionContent {
        public Text {
            paragraphs = List.copyOf(paragraphs);
        }

        @Override
        public String kind() {
            return "text";
        }
    }

    /** WORK EXPERIENCE, PROJECTS, EDUCATION. */
    record Entries(List<Entry> entries) implements SectionContent {
        public Entries {
            entries = List.copyOf(entries);
        }

        @Override
        public String kind() {
            return "entries";
        }
    }

    /** CORE TECHNOLOGIES: "Label: value; value". */
    record Categories(List<Category> categories) implements SectionContent {
        public Categories {
            categories = List.copyOf(categories);
        }

        @Override
        public String kind() {
            return "categories";
        }
    }

    /** SOFT SKILLS, LANGUAGE, INTERESTS...: a bullet list. */
    record BulletList(List<TextItem> items) implements SectionContent {
        public BulletList {
            items = List.copyOf(items);
        }

        @Override
        public String kind() {
            return "list";
        }
    }

    /** Short lines without bullets, e.g. the recipient's address in a letter (ADR-0026). */
    record Lines(List<TextItem> lines) implements SectionContent {
        public Lines {
            lines = List.copyOf(lines);
        }

        @Override
        public String kind() {
            return "lines";
        }
    }
}
