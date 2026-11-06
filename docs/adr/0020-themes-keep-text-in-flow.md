# ADR-0020: Theme rule: content text stays in normal flow (reading order)

- Status: Accepted (raised by the developer during implementation, ratified by the lead architect).
  Supersedes the "tables or floats" remark in ADR-0002: multi-column layouts use **tables only**.

## Problem
A bullet dot placed with `position: absolute` looked right, but OpenHTMLtoPDF paints out-of-flow boxes in a
separate layer. The PDF content stream then listed all bullet texts after all headings, which is the wrong
reading order for ATS, screen readers and copy/paste.

## Decision
- Text-bearing elements use only normal flow: blocks, inline-blocks, tables. No `absolute`/`fixed`/`float`.
- Multi-column regions (contact grid, header) use tables.
- Decorative elements without text may be positioned, but tables are preferred. `@page` margin boxes (page numbers) are allowed.
- Bullet hanging indent: in-flow `inline-block` dot with a negative margin.

## Alternatives considered
- **`list-style` markers**: limited control over the dot in OpenHTMLtoPDF; acceptable if a theme is fine with the default.
- **Post-process the content stream order**: fragile.

## Consequences
The E2E test asserts reading order (heading < its bullets < next heading). Every new theme must pass the same test.
