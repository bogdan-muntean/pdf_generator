# ADR-0026: Cover letters

- Status: Accepted (owner request; questions Q43–Q46 answered by the lead architect). Extends ADR-0015 (adds the
  `letter` root), ADR-0008 (adds the `lines` kind and per-type kind rules) and ADR-0017 (adds Liberation Serif).

## Request
"Separate CVs from cover letters; when a new document is created, choose CV or cover letter. For cover letters
take this one as the example (`Bogdan_Muntean_cover_letter.pdf`) and make the same functionality fit it as for CVs."

Reference: A4, one page, Times New Roman 12 pt, black, left-aligned. Sender block (bold name; location;
bold underlined email link | bold phone; GitHub | LinkedIn | My Website links), recipient lines (first bold),
bold salutation, paragraphs, bold closing, bold signature.

## Decisions

| # | Question | Decision | Alternatives rejected |
|---|----------|----------|-----------------------|
| Q43 | Data model | `meta.type: cover-letter`, **same root shape** (`meta`, `person`, `sections`) plus a reserved, set-only root **`letter`**: `salutation`, `closing` (required), `signature` (default `person.name`, in the domain), `date`, `subject` (optional free text, never "today": rendering stays deterministic). Sections: `contact` (sender), `recipient` of the new kind **`lines`**, `body` of kind `text`. `RESERVED_ROOTS` becomes the ordered list `meta, person, letter` (fixed diff order); `OverlayDiff` diffs all three; `letter` is a reserved section id. **One place for per-type rules:** `DocumentTypeRules` (used by the mapper and sent to the editor). | Letter fields as one-item text sections (the theme would guess by id; untyped); a separate model and engine (duplicates overlay, diff and editor). |
| Q43 | Per-type rules | **cv:** kinds contact, text, entries, categories, list; `person` name/jobTitle/photo; QR allowed; `letter` rejected. **cover-letter:** kinds contact (at most 1), lines, text; `person` name only (`jobTitle`, `photo` rejected); no QR; `letter` required; contact `column` ignored. Data that would silently not be rendered is rejected (fail fast). | Allowing `lines` in CVs (no CV theme renders it yet); ignoring `photo`/`qr` in letters (silent data loss). |
| Q44 | Theme + fonts | `themes/cover-letter/classic/`: A4, 2.2/2 cm margins, `"Times New Roman", "Liberation Serif"` 12 pt, line height matching the reference, bold dark-blue underlined links, titles not printed, text in normal flow (ADR-0020), GSUB off (ADR-0019). Times comes from the system font folder; **Liberation Serif 2.1.5** (SIL OFL 1.1) is bundled **from the official release** with its licence (`fonts/LICENSE-LiberationFonts.txt`). Sender lines grouped by type: location / email \| phone / github \| linkedin \| website \| other; a link shows its label or a default per type; email becomes `mailto:`. | Bundling Times New Roman (licence forbids it); system font only (tests would depend on the machine); TeX Gyre Termes (fine, but Liberation is the family already on this machine); copying Liberation from `C:\Windows\Fonts` (unknown provenance, no licence file). |
| Q45 | UI | Sidebar groups **CVs**, **Cover letters** and (only if needed) **Needs fixing**, each a variant tree (ADR-0025). `DocumentSummary.type` is read from the chain's base. *+ new document* asks for name **and type**. The editor gets `rules` with the document: *New section* offers only allowed kinds (contact hidden once there is one), the **Letter** card appears for letters (salutation, closing, signature with the name as placeholder, date, subject; clearing an optional field removes it, an `unset` in a variant), Job title / Photo / QR / Column are hidden where not used. | Inferring the type in the browser (variant files have no `meta.type`); separate pages for letters; choosing a type when creating a variant (it is inherited). |
| Q46 | Content + tests | Owner's real content (git-ignored, ADR-0010): `data/cover-letter-en.yaml` (placeholders `[Company Name]`… kept in the base; per-company variants fill them) and `data/cv-ro.yaml` (Romanian base, comma-below ș/ț). Committed fictional samples: `cover-letter-sample.yaml` + `cover-letter-sample-initech.yaml`. End-to-end checks with bundled fonts: one page, reading order (sender, date, recipient, subject, salutation, body, closing, signature), bold runs from PDFBox font names, `mailto:` and URL link annotations. | Testing with the owner's letter (personal data, not deterministic); a `{{company}}` placeholder feature (variants already do it). |

## Follow-up from the owner (Q47)
"CV and cover letter must be **separate modes**; creating a new document lets me choose the type."

| # | Question | Decision | Alternatives rejected |
|---|----------|----------|-----------------------|
| Q47 | Separate modes | A **CVs \| Cover letters** switch at the top of the sidebar (tabs with a count). Each mode lists only its own documents (as a variant tree); *Needs fixing* shows in both. Switching mode closes an open document of the other type (asking first if it has unsaved changes). Opening or creating a document switches to its mode. The mode is remembered in the browser (`localStorage`). *+ new document* starts on the current mode's type and still lets you pick CV or Cover letter (radio buttons). The editor header shows a *CV* / *Cover letter* badge. | Two groups in one list (the first version: the owner found it not separate enough); separate pages/URLs per type (more UI for the same editor). |

Note: the errors the owner saw (`unknown field(s) [letter]`, `unsupported type 'cover-letter'`) came from the
**previously installed version** in `app/` reading the new `data/cover-letter-en.yaml`; installing this version
fixes them.

## Consequences
- The jar grows by about 1.6 MB (four Liberation Serif TTFs).
- A whole chain has one type, because variants cannot change `meta.type` (ADR-0015).
- `list()` resolves each chain to its base to read the type (cheap at this scale).
- Possible later: warn when a rendered letter still contains `[...]` placeholders (PLAN).
