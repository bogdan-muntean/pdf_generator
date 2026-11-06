# ADR-0019: Disable OpenType glyph substitution (ligatures) so PDF text extracts correctly

- Status: Accepted (raised by the developer during implementation, ratified by the lead architect)

## Problem
PDFBox 3 applies GSUB ligatures ("ti", "ft", "tf") when drawing text. Calibri **and** Carlito map those
ligature glyphs to unrelated code points. The PDF *looks* right, but its text extracts as
"OpƟmized", "SoŌware", "Porƞolio". That breaks copy/paste and **applicant tracking systems (ATS)**,
which parse CVs as text.

## Decision
`FontRegistry` parses every TTF itself (`TTFParser`), calls `TrueTypeFont.setEnableGsub(false)`, and passes
the resulting `PDType0Font`s to OpenHTMLtoPDF (`builder.usePDDocument(pdf)` + `PDFontSupplier`).

## Rationale
Correct text matters more than ligatures, which are invisible at 10–12 pt. The fix sits in one adapter class.
Kerning (GPOS/`kern`) is unaffected.

## Alternatives considered
- **Zero-width non-joiners between letters**: a hack that pollutes the text.
- **Disable only `liga`/`clig`**: same result for Latin text, more code. Revisit if a theme needs GSUB features (small caps).
- **Different fonts**: changes the look of the reference CV.

## Consequences
Guarded by E2E tests (Carlito always; Calibri when installed). Verified: re-enabling GSUB makes 3 tests fail.
Fonts are embedded as subsets and closed after the PDF is saved.
