# Project plan: pdf_generator

Local-only generator for career documents (CV first, cover letters next).
Decisions behind this plan: `docs/adr/`.

## Goals
1. [x] Keep **one base CV** (per language) as structured data.
2. [x] Create **variants** that change item content, add/remove items or bullets, reorder sections/items,
   rename section titles, or override person fields (e.g. job title), without copying the base.
3. [x] Produce a PDF visually close to the current CV (`CV_Bogdan_Muntean.pdf`): photo header,
   uppercase section titles with an underline bar, contact grid with icons and a QR code,
   bullet lists, page numbers.
4. [x] Run entirely offline on Windows.

## Architecture (hexagonal)

```
              ┌──────────────── adapters ─────────────────┐
  CLI (picocli) ─┐                                         │
  Web viewer ────┼─▶ application (use cases + ports) ─▶ domain
                 │        ▲            ▲                   │
  YAML files ────┘  DocumentRepository PdfRenderer         │
  Thymeleaf + OpenHTMLtoPDF + ZXing ───┘                   │
              └────────────────────────────────────────────┘
```

| Layer | Package | Contents |
|-------|---------|----------|
| domain | `com.pdfgen.domain.tree` | immutable raw tree helpers |
| domain | `com.pdfgen.domain.overlay` | variant ops (`set`/`add`/`remove`/`order`), `OverlayEngine` |
| domain | `com.pdfgen.domain.model` | typed records: `Document`, `Person`, `Section`, sealed `SectionContent` |
| domain | `com.pdfgen.domain.mapping` | raw tree to typed model + validation |
| application | `com.pdfgen.application` | `DocumentService` use case, ports `DocumentRepository`, `PdfRenderer` |
| adapters | `com.pdfgen.adapters.storage` | YAML file repository (confined to data root) |
| adapters | `com.pdfgen.adapters.render` | Thymeleaf HTML, OpenHTMLtoPDF, QR, fonts |
| adapters | `com.pdfgen.adapters.web` | REST endpoints + static viewer page |
| adapters | `com.pdfgen.adapters.cli` | `render`, `list` commands |

Pipeline: `load base tree → validate base → load variant → apply ops → map + validate → HTML → PDF`.

## Data layout

```
data/                         (git-ignored, the owner's real documents)
  cv-en.yaml                  base CV (English)
  cv-en-qa.yaml               variant: extends cv-en
  assets/photo.jpg
samples/                      (committed, fictional, used by tests)
output/                       (git-ignored, generated PDFs)
```

## Phases

### Phase 0: Foundations (done first)
- [x] `CLAUDE.md` with architecture and engineering practices
- [x] `lead-architect` agent, ADR log
- [x] This plan

### Phase 1: MVP, CV generation (done)
1. [x] Maven project, Spring Boot 4, layered packages, ArchUnit rule.
2. [x] Domain: raw tree, overlay engine + operations, mapper + validation (unit tests first).
3. [x] YAML repository adapter (path confinement, clear errors).
4. [x] Theme `classic` reproducing the reference CV (Thymeleaf + CSS, Calibri/Arial with Carlito fallback,
   icons from the Font Awesome icon font (ADR-0017), QR via ZXing, page numbers).
5. [x] PDF renderer (OpenHTMLtoPDF, deterministic metadata).
6. [x] CLI: `render <docId> [-o file]`, `render --all`, `list`.
7. [x] Web viewer: list documents, live preview, auto-refresh on file change, download.
8. [x] Samples + end-to-end tests (text order, page count, fonts embedded, diacritics).
9. [x] Owner's real CV converted to `data/cv-en.yaml` + an example variant.
10. [x] `docs/DATA_FORMAT.md`, README, `.bat` launchers.

### Phase 1b: Desktop app + in-app editor (done)
- [x] Desktop shortcut, automatic free port, single instance, clean Quit that frees the port (ADR-0021)
- [x] Editor in the app: edit sections, items and bullets, reorder, add or remove; live draft preview; save with backup
  and conflict detection; variants saved as a diff from their base (ADR-0022)
- [x] Undo / redo (ADR-0023)
- [x] Theme hover states, new documents from scratch, photo upload with EXIF removal (ADR-0024)
- [x] Variant chains: a variant of a variant, up to 5 levels (ADR-0025)

### Phase 2: Cover letters (done)
- [x] `type: cover-letter` with the `letter` root (salutation, closing, signature, date, subject), sender
  `contact`, recipient `lines`, body `text`; own theme (Times New Roman / Liberation Serif) matching the
  owner's letter; CVs and cover letters grouped separately; type chosen when creating a document (ADR-0026)
- [x] Romanian base CV `data/cv-ro.yaml`; base cover letter `data/cover-letter-en.yaml`
- Variant per company (e.g. `cover-letter-en-luxoft`) extending the base letter: made in the app with *+ variant*.
- [x] Second letter theme `modern` and a theme catalog; Theme is a select in the editor (ADR-0027);
  `data/cover-letter-en-marsh.yaml`
- [x] Backup / restore of all data as one ZIP, and a shortcut in the project folder (ADR-0028)
- [x] Delete any document together with its variants, with a recoverable copy (ADR-0029)

### Follow-ups (not built, only on real need)
- Merge import of a backup (add only the missing documents); today a restore replaces everything (ADR-0028).
- A CV in the modern style (ADR-0027).
- Duplicate a base document (e.g. to start `cv-de` from `cv-en`).
- Warn when a rendered letter still contains `[...]` placeholders.

### Phase 3: Nice-to-have (only on real need)
- More themes, visual regression tests.

## Risks and mitigations
| Risk | Mitigation |
|------|-----------|
| CSS 2.1 limits (no flexbox) | tables/floats; renderer behind a port (Chromium swap possible) |
| Calibri missing on another machine | Carlito bundled fallback |
| Variant breaks when the base changes | id-based targets, fail-fast errors naming file/op/path |
| Personal data leaking via git | `data/` git-ignored, fictional `samples/` |
