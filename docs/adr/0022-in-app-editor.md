# ADR-0022: In-app document editor (supersedes "viewer, not editor" in ADR-0003)

> Update (ADR-0029): base documents can now be deleted from the app, together with their variants.

- Status: Accepted (owner request; questions Q26–Q33 answered by the lead architect). Q32 partly superseded by ADR-0024 (photo upload), extended by ADR-0025 (variant chains) and ADR-0026 (cover letters)

## Request
"Generate the PDF from a template I can modify **in the app**: when I want to change something, I change
exactly that content and section." Interpretation: the template is the base CV and its variants. Theme
(HTML/CSS) editing was not requested.

## Decisions

| # | Question | Decision | Alternatives rejected |
|---|----------|----------|-----------------------|
| Q26 | Frontend | **React + TypeScript** (Vite) in `frontend/`, built by `frontend-maven-plugin` into the jar (`mvn verify` also runs the Vitest tests). Few dependencies: no UI kit, no state library, no drag-and-drop (↑ ↓ buttons). | Vanilla JS (unmaintainable at editor size); Thymeleaf/htmx forms (nested dynamic lists are painful). |
| Q27 | API | Document-level: `GET /api/documents/{id}/edit`, `PUT /api/documents/{id}` (with `If-Match`), `POST /api/preview` (unsaved draft). Validation errors come back as `[{path, message}]` with id-based paths, so the editor highlights the exact field. | Fine-grained REST per section/item: many endpoints, no atomic save. |
| Q28 | Editing a variant | The editor shows the **resolved** document. On save, `OverlayDiff` computes the operations between the base and the edited tree, and they become the variant file. Invariant tested: `apply(base, diff(base, edited)) == edited`; the service also re-checks it before writing. Elements show badges ("changed in this variant" / "only in this variant") with *revert to base*. | Recording operations from clicks (fragile); editing raw operations (too technical). |
| Q29 | Writing YAML | Canonical writer: stable order, block style, short leaf maps in flow style, multi-line text as `|` blocks, conservative quoting. The previous file is backed up to `data/.history/<id>/` (last 20 kept). Tested: `load(write(t)) == t` for 49 tricky strings and all samples. | Comment-preserving YAML (no robust Java library); switching to JSON (loses readability). |
| Q30 | Concurrency | Version = SHA-256 of the file (base + variant for a variant). A stale `If-Match` gets 409 and the editor offers *Reload*. The editor also notices changes on disk while open. | Last write wins: silently loses edits made in VS Code. |
| Q31 | Save model | Explicit **Save** (+ Ctrl+S), dirty indicator, leave-page guard. The preview re-renders the draft 0.5 s after typing stops; stale requests are cancelled and the server renders one at a time. | Autosave: floods the history and writes half-typed variant diffs. |
| Q32 | Extra scope | In: *+ variant* (new empty variant of a base), *delete* variant (backup kept), photo picked from `data/assets`. Out (deferred): duplicating a base, photo upload. | — |
| Q33 | Ids | New sections and items get a slug of their title, which follows the title while the element is unsaved and is fixed after the first save. Saving a base gives ids to plain-text bullets, list items and paragraphs, so later variants can target them one by one. In a variant, new plain-text items get ids before saving. | Random ids (unreadable in YAML); ids typed by hand (error-prone). |

## Implementation notes
- `set` gained `unset: [field, ...]`, so a variant can remove a field (for example the job title or the QR code).
- Overrides are computed in the browser by comparing the draft with the base tree, so they also reflect unsaved edits.
  The server does not send a separate list of overridden paths.
- The mapper's problems became structured `Problem(path, message)` records.
- Known limitation: in a variant saved while its base still had plain-text bullets (before the base's first save from
  the app), a change to those bullets stores the item's whole bullet list. Once the base has been saved in the app, edits are granular.

## Consequences
- The first `mvn package` downloads Node 24 into `frontend/node` (git-ignored); later builds reuse it.
- Hand-written YAML loses its comments on the first save from the app. The editor warns once, and the old file stays in `.history`.
