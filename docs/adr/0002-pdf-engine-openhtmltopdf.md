# ADR-0002: OpenHTMLtoPDF (PDFBox) as PDF engine, behind a port

- Status: Accepted

## Decision
Render HTML/CSS to PDF with `io.github.openhtmltopdf:openhtmltopdf-pdfbox` (maintained fork),
behind a `PdfRenderer` port so the engine can be swapped.

## Rationale
- Pure Java, offline, embeds fonts, deterministic.
- Supports CSS paged media: `@page` margin boxes + `counter(page)` (page numbers bottom-right),
  page breaks, repeating table headers.
- The reference CV layout (photo header, 2-column contact grid) works with tables/floats; no flexbox needed.
- HTML/CSS keeps content separate from presentation; a theme is just a template + CSS.

## Alternatives considered
- **Playwright/Chromium**: modern CSS (flex/grid), but ~150 MB browser download and version-dependent output.
  Best choice if themes ever need flex/grid; the port makes the swap cheap.
- **Direct PDFBox / iText drawing**: layout and pagination hand-coded in Java; iText is AGPL.
- **Typst / LaTeX**: external binary, new template language to learn.
- **JasperReports**: report-oriented, heavy, poor for flowing prose.

## Consequences
CSS 2.1 + paged media only. LGPL-2.1 (allowed). Calibri is proprietary, so it is never committed: it is loaded
from a configurable font directory (default `C:\Windows\Fonts`). Carlito (OFL, metric-compatible) is the fallback.
