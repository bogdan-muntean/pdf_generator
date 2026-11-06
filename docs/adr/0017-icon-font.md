# ADR-0017: Contact icons from an embedded icon font (Font Awesome Free)

- Status: Accepted

## Question
The original ODG has the icons only as 33x33 px PNGs, which are blurry in print.

## Decision
Bundle Font Awesome Free 6.7.2 (`fa-solid-900.ttf`, `fa-brands-400.ttf`). The theme maps `contact.type`
to a glyph (`ThemeHelper.icon`), so icons belong to the theme, not the data.

## Rationale
Vector, sharp in print, colour and size set in CSS. OpenHTMLtoPDF already embeds TTFs, so no new rendering library is needed.

## Alternatives considered
- **SVG + Batik** (`openhtmltopdf-svg-support`): most flexible, but about 10 MB of jars for six icons.
- **Reuse the 33 px PNGs**: blurry.
- **High-res PNGs committed**: acceptable fallback; raster, and recolouring means regenerating them.
- **No icons**: departs from the reference design.

## Consequences
Attribution in `THIRD-PARTY-NOTICES.md` (font OFL-1.1, icons CC BY 4.0).
