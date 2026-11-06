package com.pdfgen.adapters.render;

import com.pdfgen.domain.model.Category;
import com.pdfgen.domain.model.ContactItem;
import com.pdfgen.domain.model.ContactType;
import com.pdfgen.domain.model.Document;
import com.pdfgen.domain.model.Entry;
import com.pdfgen.domain.model.Letter;
import com.pdfgen.domain.model.QrCode;
import com.pdfgen.domain.model.SectionContent;
import com.pdfgen.domain.model.TextItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Presentation logic exposed to templates as {@code ${t}}. Keeps templates free of Java
 * {@code Optional} handling (Thymeleaf 3.1 restricts calls on {@code java.*} objects) and holds the
 * icon map, which belongs to the theme rather than the data (ADR-0017).
 */
public final class ThemeHelper {

    /** A glyph of one of the bundled icon fonts, optionally drawn on a round badge. */
    public record Icon(String fontClass, String glyph, String badgeClass) {
    }

    /** Value or {@code null}, so templates can write {@code th:if="${t.opt(x)}"}. */
    public String opt(Optional<String> value) {
        return value.orElse(null);
    }

    public List<ContactItem> leftColumn(SectionContent.Contact contact) {
        return contact.column(ContactItem.Column.LEFT);
    }

    public List<ContactItem> rightColumn(SectionContent.Contact contact) {
        return contact.column(ContactItem.Column.RIGHT);
    }

    /** The QR code of a contact section, or {@code null}. */
    public QrCode qr(SectionContent.Contact contact) {
        return contact.qr().orElse(null);
    }

    public Icon icon(ContactType type) {
        return switch (type) {
            case LOCATION -> new Icon("fa-solid", "", "plain");
            case PHONE -> new Icon("fa-solid", "", "badge dark");
            case EMAIL -> new Icon("fa-solid", "", "badge dark");
            case WEBSITE -> new Icon("fa-solid", "", "badge dark");
            case GITHUB -> new Icon("fa-brands", "", "plain");
            case LINKEDIN -> new Icon("fa-brands", "", "badge linkedin");
            case OTHER -> new Icon("fa-solid", "", "plain");
        };
    }

    /** Explicit {@code href}, or a sensible default for emails, phones and web addresses. */
    public String href(ContactItem item) {
        if (item.href().isPresent()) {
            return item.href().get();
        }
        String value = item.value().strip();
        return switch (item.type()) {
            case EMAIL -> "mailto:" + value;
            case PHONE -> "tel:" + value.replace(" ", "");
            case WEBSITE -> value.startsWith("http") ? value : "https://" + value;
            default -> null;
        };
    }

    /** {@code "HTML5; CSS3; React."} — values joined with "; " and closed with a full stop. */
    public String values(Category category) {
        String joined = String.join("; ", category.values());
        return joined.endsWith(".") ? joined : joined + ".";
    }

    /** Bullets of an entry that are kept on the same page as its heading. */
    public List<TextItem> leadBullets(Entry entry) {
        return entry.bullets().subList(0, Math.min(LEAD_BULLETS, entry.bullets().size()));
    }

    public List<TextItem> remainingBullets(Entry entry) {
        return entry.bullets().subList(Math.min(LEAD_BULLETS, entry.bullets().size()), entry.bullets().size());
    }

    /** Caption text before the {@code {url}} placeholder (or the whole caption). */
    public String captionBefore(QrCode qr) {
        String caption = qr.caption().orElse("");
        int at = caption.indexOf(URL_PLACEHOLDER);
        return at < 0 ? caption : caption.substring(0, at);
    }

    /** Caption text after the {@code {url}} placeholder, or {@code null} if there is no placeholder. */
    public String captionAfter(QrCode qr) {
        String caption = qr.caption().orElse("");
        int at = caption.indexOf(URL_PLACEHOLDER);
        return at < 0 ? null : caption.substring(at + URL_PLACEHOLDER.length());
    }

    public boolean captionHasUrl(QrCode qr) {
        return qr.caption().orElse("").contains(URL_PLACEHOLDER);
    }

    // ---- cover letters (ADR-0026) ---------------------------------------------------------------

    /**
     * Contact items grouped into lines, for letter themes (ADR-0026, ADR-0027). {@code spec} is chosen by the
     * template: groups separated by {@code |}, contact types by {@code ,}, e.g.
     * {@code "location|email,phone|github,linkedin,website,other"}. Within a line, items keep the order of the
     * data; empty lines are dropped; types not listed are not shown.
     */
    public List<List<ContactItem>> contactLines(SectionContent.Contact contact, String spec) {
        List<List<ContactItem>> lines = new ArrayList<>();
        for (String group : spec.split("\\|")) {
            Set<ContactType> types = new java.util.HashSet<>();
            for (String key : group.split(",")) {
                ContactType.fromKey(key.strip()).ifPresent(types::add);
            }
            List<ContactItem> line = contact.items().stream().filter(item -> types.contains(item.type())).toList();
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    /** True for web profiles (GitHub, LinkedIn, website, other with a link): shown with their label. */
    public boolean isProfileLink(ContactItem item) {
        return switch (item.type()) {
            case GITHUB, LINKEDIN, WEBSITE -> true;
            case OTHER -> letterHref(item) != null;
            default -> false;
        };
    }

    /** True for items shown as an underlined link in a letter: email and web profiles (not location, phone). */
    public boolean isLetterLink(ContactItem item) {
        return item.type() != ContactType.LOCATION && item.type() != ContactType.PHONE && href(item) != null;
    }

    /**
     * Text of a letter's sender item: for web profiles the label ("GitHub", "My Website"), else a default per
     * type; for email, phone and location the value itself.
     */
    public String letterText(ContactItem item) {
        return switch (item.type()) {
            case EMAIL, PHONE, LOCATION -> item.value();
            default -> item.label().map(ThemeHelper::withoutColon).filter(label -> !label.isBlank())
                    .orElse(switch (item.type()) {
                        case GITHUB -> "GitHub";
                        case LINKEDIN -> "LinkedIn";
                        case WEBSITE -> "Website";
                        default -> item.value();
                    });
        };
    }

    /** Link target in a letter; like {@link #href} but a web profile without {@code href} links to its value. */
    public String letterHref(ContactItem item) {
        String href = href(item);
        if (href != null) {
            return href;
        }
        String value = item.value().strip();
        return value.startsWith("http") ? value : null;
    }

    /** Index of the first section of {@code kind}, or -1. */
    public int firstIndexOfKind(Document document, String kind) {
        for (int i = 0; i < document.sections().size(); i++) {
            if (document.sections().get(i).kind().equals(kind)) {
                return i;
            }
        }
        return -1;
    }

    public Letter letter(Document document) {
        return document.letter().orElseThrow(() -> new IllegalStateException("not a cover letter"));
    }

    private static String withoutColon(String label) {
        String stripped = label.strip();
        return stripped.endsWith(":") ? stripped.substring(0, stripped.length() - 1).strip() : stripped;
    }

    private static final int LEAD_BULLETS = 2;
    private static final String URL_PLACEHOLDER = "{url}";
}
