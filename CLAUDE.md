# CLAUDE.md

Guidance for Claude (and any human contributor) working in this repository.

## 1. Project in one paragraph

A **local-only** PDF generator for personal career documents. First target: the owner's CV
(reference: `D:\Documente-B\aplicari firme\CV_Bogdan_Muntean.pdf`). Next target: cover letters.
The owner keeps a **base template** of the CV and creates **variants** that change section
content, items inside sections, section order, and section titles — without duplicating the
whole document. Nothing is deployed online; the app runs on the owner's Windows machine only.

## 2. Decision protocol (important)

The owner has explicitly asked **not to be consulted on technical choices**.

- When a decision is needed, consult the **lead software architect** agent
  (`.claude/agents/lead-architect.md`) instead of asking the owner.
- Every non-trivial decision is recorded as an ADR in `docs/adr/NNNN-title.md`
  (context, options considered, decision, consequences). The ADR log is how the owner
  later learns *what* was asked, *what* was answered, *why*, and *which alternatives existed*.
- Only escalate to the owner for things that are genuinely theirs: personal data/content of
  the CV, or anything irreversible outside this repository.

## 3. Architecture principles

1. **Separation of concerns / layered (hexagonal) architecture.**
   `domain` (pure model + rules, no frameworks) → `application` (use cases, ports) →
   `adapters` (web, file storage, PDF rendering). Dependencies point **inward only**.
   The domain never imports Spring, Jackson, or a PDF library.
2. **Content ≠ presentation.** Document data (YAML/JSON) is independent from layout
   (HTML/CSS template). The same content must be renderable by another theme without changes.
3. **Data over code.** CVs, variants and cover letters are data files, human-readable,
   diff-able and versioned in git. No database unless a concrete need appears.
4. **Stable identifiers.** Every section and item has a stable `id`. Variants reference
   ids, never array indexes or titles — so reordering/renaming the base never breaks a variant.
5. **Composition over duplication.** A variant is a *patch* over a base, not a copy.
6. **KISS & YAGNI.** Build what is needed now (CV), design seams for what is announced next
   (cover letters, new themes). Do not build speculative features.
7. **SOLID**, especially Single Responsibility and Dependency Inversion (use cases depend on
   ports/interfaces; adapters implement them).
8. **Immutability.** Domain objects are Java `record`s / unmodifiable collections.
   Applying a variant produces a *new* document; it never mutates the base.
9. **Fail fast, validate at the boundary.** Input files are validated on load with clear,
   actionable error messages (file, path, what is wrong). Inside the core, trust the types.
10. **Determinism.** Same input → same PDF content. Fonts are embedded; no network access
    during rendering (QR codes and images are generated/loaded locally).
11. **Security even when local.** The web server binds to `127.0.0.1` only. Template
    rendering escapes user text by default. Path inputs are confined to the data directory
    (no path traversal). No telemetry, no external calls.
12. **Explicit over clever.** Readable names, small methods, no magic. Comments explain *why*,
    not *what*.

## 4. Engineering practices

- **Language & stack:** see `docs/adr/` (stack decisions live there, not here).
- **Build:** single command build and test (`mvn verify`). The build must pass before any
  task is considered done.
- **Testing pyramid:**
  - Unit tests for domain logic (variant merge, reorder, rename, validation) — the majority.
  - Integration tests for adapters (YAML loading, HTML rendering, PDF generation).
  - At least one end-to-end test: sample data → PDF file → assert text/page properties.
  - Tests are deterministic and independent of the owner's personal files.
- **Errors:** domain-specific exceptions with meaningful messages; never swallow exceptions;
  never return `null` from public APIs (use `Optional` or empty collections).
- **Logging:** SLF4J, no `System.out` in production code; no personal data in logs at INFO+.
- **Configuration:** externalized (`application.yml`, CLI args); no hard-coded absolute paths.
- **Dependencies:** minimal, well-maintained, license-compatible (prefer Apache-2.0/MIT/LGPL).
  Every new dependency must be justified (ADR or commit message).
- **Code style:** Java conventions, 4-space indent, packages by feature inside each layer,
  public API documented with short Javadoc where intent is not obvious.
- **Git:** small, focused commits with imperative messages; never commit personal data that the
  owner hasn't put in the repo themselves; generated PDFs go to `output/` (git-ignored).
- **Documentation:** `README.md` (how to run), `docs/PLAN.md` (roadmap), `docs/adr/` (decisions),
  `docs/DATA_FORMAT.md` (how to write a CV / variant file).

## 5. Definition of Done

A change is done when:
1. It builds and **all tests pass** (`mvn verify`).
2. New behaviour is covered by tests.
3. Relevant docs (README / DATA_FORMAT / ADR) are updated.
4. The generated sample PDF was actually produced and inspected (not just "tests green").
5. No TODOs left without an issue/plan entry.

## 6. Repository map (keep updated)

```
CLAUDE.md                 this file
README.md                 how to run
docs/PLAN.md              project plan / roadmap
docs/adr/                 architecture decision records
docs/DATA_FORMAT.md       data file reference
.claude/agents/           lead-architect agent definition
src/main/java/...         application code (domain / application / adapters)
src/main/resources/       themes/index.txt + themes/<type>/<name>/ (cv/classic, cover-letter/classic|modern), fonts/, launcher/ (icons), application.yml
frontend/                 editor UI: React + TypeScript + Vite, built into target/classes/static (ADR-0022)
samples/                  fictional CVs, cover letters and a 3-level variant chain used by tests and docs (committed)
data/                     owner's real documents + assets (git-ignored, ADR-0010)
output/                   generated PDFs (git-ignored)
start.bat / render.bat    launchers (ADR-0012)
Install PDF Generator.bat double-click installer: runs create-shortcut.ps1 (ADR-0030)
installer/                JavaSetup.ps1 (find JDK 21+ or install via winget) + its test script (ADR-0030)
mvnw.cmd, .mvn/wrapper/   Maven Wrapper, pinned and checksum-verified Maven (ADR-0030)
create-shortcut.ps1       Desktop + project-folder shortcut: javaw + tray, auto port, single instance (ADR-0021, ADR-0028)
run/, logs/               runtime lock/port file and logs (git-ignored)
```

Package layout: `com.pdfgen.domain` (tree, overlay, model, mapping) → `com.pdfgen.application`
(DocumentService + ports) → `com.pdfgen.adapters` (storage, render, web, cli, desktop) ← `com.pdfgen.bootstrap`
(Spring wiring). `ArchitectureTest` enforces it.

## 7. Lessons learned (do not regress)
- PDF text must extract correctly (ATS): GSUB ligatures are disabled (ADR-0019), and theme text stays
  in normal flow (ADR-0020). The E2E tests guard both.
- Thymeleaf 3.1 blocks method calls on `java.*` objects such as `Optional` in templates: expose values through `ThemeHelper`.
- In YAML flow lists `[a, b]`, quote values containing commas.
- Variants are saved as a diff (`OverlayDiff`); keep `apply(base, diff(base, edited)) == edited` true and tested.
  The YAML writer must keep `load(write(t)) == t` (CanonicalYamlWriterTest).
- Never rebuild the jar a running app uses: the shortcut runs the installed copy in `app/` (ADR-0021).
- Per-type rules (allowed kinds, `letter`, photo, QR) live only in `DocumentTypeRules`; the editor receives them
  from the server (ADR-0026). Variant chains are resolved only by `DocumentService.loadChain` (ADR-0025).
- Uploaded files: type from magic bytes, never overwrite, serve only allow-listed paths (ADR-0024).
- A new theme needs its folder AND a line in `themes/index.txt` (ThemeCatalogTest, ADR-0027).
- Backups/restores: validate everything before changing anything, write a safety ZIP, roll back on failure; never touch
  `.history` (ADR-0028). Deletes are all-or-nothing over the whole variant subtree, checked against the list the user
  confirmed (ADR-0029).
