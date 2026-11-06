# ADR-0018: Repeated section titles and keep-together rules in the MVP

- Status: Accepted

## Decision
- `entries` sections render as a table: title in `<thead>`, `-fs-table-paginate: paginate`, so the title repeats on each page.
- Each entry is a row with its heading, dates and **first two bullets** (`page-break-inside: avoid`).
  Every further bullet is its own row, so no table row is ever taller than a page.
- Section titles: `page-break-after: avoid`; `orphans`/`widows` are 2.

## Alternatives considered
- **Defer**: the reference CV shows repeated titles, so they are a requirement.
- **Keep whole entries together**: wastes page space for long entries.

## Consequences
The E2E test asserts that page 2 starts with the repeated section title.
