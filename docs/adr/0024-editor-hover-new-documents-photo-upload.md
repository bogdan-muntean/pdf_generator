# ADR-0024: Editor: theme hover states, new documents, photo upload

- Status: Accepted (owner request; questions Q38–Q41 answered by the lead architect). Extends ADR-0022 and
  partly supersedes its Q32: photo upload is now **in**; duplicating a base stays deferred.

## Request
"When I hover the buttons, highlight them in a way specific to the theme. Let the user make a new document
from scratch. Add attaching a photo that can be set as Photo."

## Decisions

| # | Question | Decision | Alternatives rejected |
|---|----------|----------|-----------------------|
| Q38 | Hover "specific to the theme" | All UI colours are **design tokens** in `:root` of `frontend/src/styles.css`. The accent is the classic theme's blue (`#2f80c9`). Every button style has a hover/press state derived from the tokens: default → accent border, soft accent fill, darker accent text (`--accent-strong`, for contrast); primary → darker accent; danger → soft red; links → underline; dashed *+ add* → solid; document list → accent bar. All rules are scoped to `:not(:disabled)`. A separate `:focus-visible` ring is visible with the keyboard alone. 120 ms transition, off under `prefers-reduced-motion`. | Taking the UI accent from the CV's `meta.theme` (YAGNI with one theme, and it mixes document presentation with app chrome; the tokens make it a one-line change later); a UI kit (ADR-0022 Q26). |
| Q39 | New document from scratch | *+ new document* in the sidebar → name + type → `POST /api/documents {id, type}` → `StarterDocuments` (pure domain factory) writes a skeleton: the usual sections with **empty item lists** and no placeholder text. No `outputName`, so the PDF is named after the id. Validated by `DocumentMapper` before writing; an existing id is refused. The id rule is shared with variants. | Duplicating an existing base (deferred); a document with no sections (the user would need to know the kinds); placeholder items (they end up in a real PDF). |
| Q40 | Photo upload | *Upload photo…* next to the Photo select. The browser sends the **raw file bytes** with `Content-Type: image/jpeg` or `image/png` to `POST /api/assets/images?name=…`. Any other content type gets 415. Server: `PhotoLibrary` (application layer, pure) checks the **magic bytes**, caps the size at 10 MB (413, bounded read), sanitizes the name (slug, extension from the real type) and **strips JPEG metadata** (APP1 EXIF/GPS/XMP, APP3–13/15, COM; keeps JFIF, ICC, Adobe; lossless). `AssetSource.saveImage` stores the file in `data/assets/` via temp file + move, **never overwrites**: identical content reuses the file, different content gets `-2`, `-3`. Thumbnails: `GET /api/assets/image?path=` serves **only paths that `listImages()` returns** (allow-list), with `nosniff`. Choosing the uploaded photo is one undo step. | Multipart (a CORS-safelisted type, so any web page could post it to localhost without a preflight); base64 in the YAML (bloats files and diffs); copying by path (browsers do not give paths); re-encoding with ImageIO to clean it (lossy, not byte-deterministic). |
| Q41 | Record | This ADR. | Three separate ADRs (too much for three small editor features). |

## Security amendments (Q40)
- **CSRF guard for every endpoint:** `LocalHostOnlyFilter` now also rejects POST/PUT/DELETE/PATCH when
  `Sec-Fetch-Site: cross-site` or when `Origin` is present and not a localhost origin (any port, so the Vite
  dev server still works). Requests without these headers (scripts, the launcher) pass. This does not depend on
  content-type details.
- The type of an upload comes from its bytes, never from its name or the browser's content type.

## Consequences
- Removing EXIF also removes the orientation tag: a sideways phone photo stays sideways, in the thumbnail and in
  the PDF alike. Rotate it before uploading (written in `docs/DATA_FORMAT.md`).
- Undo reverts the Photo field, not the upload: the file stays in `data/assets/`, which is harmless.
- Bases still cannot be deleted in the app, so a mistaken new document is removed by hand (PLAN follow-up).
- Tests: `PhotoLibraryTest` (magic bytes, names, reuse/`-2`, EXIF removal, 10 MB, allow-list),
  `EditorUploadAndCreateTest` (415/413, cross-site 403, thumbnail allow-list, create with type), Vitest
  (upload request, undo, hover/token rules in `styles.css`).
