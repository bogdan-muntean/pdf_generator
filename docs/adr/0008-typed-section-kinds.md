# ADR-0008: Typed section kinds (sealed interface) + a `person` block

- Status: Accepted

## Decision
Document root: `{type, lang, theme, person, sections[]}`. Each section: `{id, kind, title, items}`.
Kinds for the CV:

| kind | used for |
|------|----------|
| `contact`    | CONTACT INFO (location, phone, email, github, linkedin, website + QR) |
| `text`       | EXECUTIVE SUMMARY (paragraphs) |
| `entries`    | WORK EXPERIENCE, PROJECTS, EDUCATION (heading, subheading, dates, bullets) |
| `categories` | CORE TECHNOLOGIES, ADDITIONAL EXPOSURE (label: values) |
| `list`       | SOFT SKILLS, LANGUAGE, INTERESTS, DRIVING LICENCE |

## Rationale
Exhaustive `switch` and real validation; a new kind is one record plus one template fragment.
Titles are data, so renaming is trivial. Cover letters later reuse `person` and `text`, plus
letter-specific fields and their own template.

## Alternatives considered
- **Fully generic blocks**: layout leaks into data, weak validation.
- **One class per CV section**: rigid; ties titles to types.
