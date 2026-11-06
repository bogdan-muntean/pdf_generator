# ADR-0011: Test pyramid with PDF text/structure assertions

- Status: Accepted

## Decision
- **Unit** (the majority): each overlay op, ordering rules, validation errors, immutability.
- **Integration**: YAML loader errors; HTML checked with jsoup (section order, titles, escaping); QR decodes back to the URL.
- **End-to-end**: sample data to PDF; PDFBox asserts page count, title order, page numbers, embedded fonts, RO diacritics, deterministic text.
- **ArchUnit**: the domain has no Spring/Jackson/PDF imports.

## Alternatives considered
- **Golden-image pixel diffs now**: brittle while the theme evolves; add later as `@Tag("visual")`.

## Consequences
The renderer pins creation date and producer metadata for deterministic output.
