# ADR-0027: Theme catalog and the "modern" cover letter theme

- Status: Accepted (owner request; questions Q48–Q49 answered by the lead architect). Extends ADR-0026 (cover letters)
  and ADR-0017 (fonts: adds Liberation Sans).

## Request
"Add this cover letter style too: `Downloads/Bogdan_Muntean_Cover_Letter.pdf`."

Reference: one page, Arial about 10 pt, dark grey text. A centred header has the name in capitals, bold and dark blue
(#1f4e79). Below it are one line with location | email | phone and one line with blue underlined GitHub |
LinkedIn | My Website links, then a dark-blue rule. The recipient's first line is bold and dark blue. The salutation
is not bold. The closing is followed directly by a bold signature. The reference is US Letter; we keep A4 (ADR-0005).

## Decisions

| # | Question | Decision | Alternatives rejected |
|---|----------|----------|-----------------------|
| Q48 | How is a second theme for the same type chosen and validated? | Theme folders `themes/<type>/<name>/` plus a **catalog** `themes/index.txt` (`type/name` lines). The `ThemeCatalog` port (application) has a classpath adapter, `ClasspathThemeCatalog`. `HtmlRenderer` refuses a theme that is not in the catalog. **Saving** a document with an unknown `meta.theme` gives a field error on `meta.theme` listing the available themes. A test checks that the index equals the folders and that each folder has `document.html` and `style.css`. | Scanning the classpath for folders (does not work reliably inside a jar); a Java enum of themes (code change per theme); checking only at render time (typos found late). |
| Q48 | Content vs. theme | The **same data** renders in both letter themes. The grouping of contact lines is chosen by the template (`ThemeHelper.contactLines(contact, "location,email,phone\|github,linkedin,website,other")`), not by the data. Capitals come from CSS (`text-transform`), so the stored name keeps its case. | Theme-specific data fields; storing the name in capitals. |
| Q49 | Editor | `rules.themes` (from the catalog, per type) is sent with the document. The *Theme* field becomes a select with only those themes. A theme typed by hand that does not exist stays visible as "x (unknown)", so the error can be seen and fixed. Switching theme in a variant is just `meta.theme`. | A free-text field (typos); hiding unknown values (the select would silently show another theme). |
| Q49 | Fonts + owner data | `"Arial", "Liberation Sans"`. Liberation Sans 2.1.5 is bundled from the official release (OFL, same licence file as Liberation Serif). Arial and its italic come from the system. Owner data: `data/cover-letter-en-marsh.yaml`, a variant of `cover-letter-en` with `meta.theme: modern` and the Marsh text (git-ignored). Sample: `samples/cover-letter-sample-modern.yaml`, a third-level variant that sets only the theme. | Bundling Arial (licence); a separate base letter for the new look (duplicated content). |

## Consequences
- Adding a theme takes a folder plus one line in `themes/index.txt`; `ThemeCatalogTest` fails if they disagree.
- `ModernLetterEndToEndTest` checks one page, reading order, capitals, the colours and weights of the name, recipient,
  salutation and links (PDF colour operators), and the link annotations.
- Follow-up (only on need): a CV in the modern style.
