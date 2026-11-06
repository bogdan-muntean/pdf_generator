# pdf-generator

Local generator for career documents: **CVs and cover letters**. Keep **one base document** as data and create
**variants** (change content, items, order or section titles) without copying the whole document. A variant can
have variants of its own (for example `cv-en` → `cv-en-fullstack` → `cv-en-fullstack-luxoft`).
It runs only on your machine and makes no network calls.

## Requirements
- Java 21 and Maven 3.9 (both on `PATH`); Node is downloaded by the build
- Windows: Calibri/Arial are used from `C:\Windows\Fonts` when available; otherwise the bundled Carlito font is used.

## Quick start

Create the desktop shortcut once. Rerun it after changing the code: it rebuilds, closes the running app
(after asking) and installs the new version into `app\`, which is what the shortcut runs.

```bat
powershell -ExecutionPolicy Bypass -File create-shortcut.ps1            :: Desktop + this folder
powershell -ExecutionPolicy Bypass -File create-shortcut.ps1 -StartMenu :: + Start Menu
```

It creates **PDF Generator** on the Desktop and also in this project folder (`PDF Generator.lnk`, git-ignored;
`-NoFolderShortcut` skips it).

Double-click **PDF Generator**:
- the app uses port 8080, or **any free port** if 8080 is taken, and opens the browser at the right address;
- a tray icon (bottom right, near the clock) offers *Open viewer* and *Quit*. Double-clicking it opens the editor;
- starting it again while it runs just opens the browser at the running copy;
- **Quit** (tray or the *Quit app* button) first stops the web server and frees the port, then closes the app.

## Editing in the app

The screen has three columns: **documents** | **editor** | **PDF preview**. At the top of the documents column
the **CVs | Cover letters** switch selects the mode: each mode lists only its own documents, with variants under
the document they extend. Files that cannot be read are listed under *Needs fixing* with the reason.

- **+ new document**: choose *CV* or *Cover letter* and a name (e.g. `cv-de`, `cover-letter-de`). It starts with
  the usual empty sections (a letter: sender, recipient, body, salutation and closing).

- Click a document. Each section is a card: change its **title**, its items and bullets, move them with ↑ ↓,
  remove them with ✕, or add new ones (*+ entry*, *+ bullet*, *+ category*, *+ add section*…).
- The preview on the right follows what you type, before you save. Problems (for example an empty heading) appear
  under the field and at the top of the editor.
- **↶ Undo / ↷ Redo** (Ctrl+Z / Ctrl+Y) step back and forward through your edits. A word typed in one field is one
  step. If you undo and then change something, the undone steps are gone and your new change becomes the latest.
  The history survives Save and is cleared when you open another document. *Discard* can be undone too.
- **Save** (or Ctrl+S) writes the file. The previous version is kept in `data\.history\`.
- **+ variant** next to any document creates a variant of it, also of a variant (up to 5 levels). A variant opens
  as the full document: edit anything, and on save only what differs from its **parent** is stored. Changed
  elements show *changed in this variant* with a *revert* button (back to the parent's version). Everything you
  did not touch keeps following the parent, and through it the base.
- **Photo** (CVs): pick one from `data\assets\`, or **Upload photo…** (JPEG/PNG, up to 10 MB). The file is copied
  into `data\assets\` and its location/camera data (EXIF/GPS) is removed.
- **Cover letters** have a *Letter* card (salutation, closing, optional signature, date and subject), the sender
  (contact lines), the recipient lines and the paragraphs. Keep `[Company Name]`-style placeholders in the base
  letter and make one variant per company.
- **Theme** (Document settings): choose among the themes of the document's type. Cover letters have *Classic*
  (Times New Roman) and *Modern* (Arial, centred blue header). A variant can switch only the theme.
- **delete** next to any document deletes it **together with every variant built on it** (2nd, 3rd level…).
  A dialog lists all of them first. A copy of the deleted files is kept in
  `data\.history\delete-<name>-<time>.zip`. Photos are not deleted.
- **Backup…** saves all documents and photos into one ZIP. Chrome/Edge ask where to save it (any folder,
  e.g. a USB stick); other browsers put it in Downloads.
- **Restore…** picks a backup ZIP and, after you confirm, **replaces all documents and photos** with the backup's.
  The file is checked completely first; if anything is wrong, nothing changes. Your data from just before is saved
  in `data\.history\restore-<time>.zip` (the last 5 are kept), so a restore can be undone by restoring that file.
  The same works for a `delete-….zip`, but as a full replace: documents created since are removed. To get back only
  the deleted files, copy them out of that ZIP into `data\`.
- Buttons are highlighted in the app's blue when you hover them; Tab shows a focus ring.
- If the file changes on disk while you edit (for example in VS Code), the editor tells you and offers to reload.
- The first save of a file written by hand rewrites it in a tidy format and drops its comments (you are asked first).

Other launchers:

```bat
start.bat                  :: same app, with a console window showing the logs (Ctrl+C = Quit)
render.bat list            :: list documents in data\
render.bat render cv-en    :: write output\CV_....pdf
render.bat render --all
```

If the shortcut does nothing visible, see `logs\pdf-generator.log`.

Without the `.bat` files:

```bat
mvn package
java -jar target\pdf-generator.jar serve
java -jar target\pdf-generator.jar render --all --pdfgen.data-dir=samples
```

## Your documents
- `data/` is **git-ignored** (personal data). It can be its own private git repository.
- Format reference: [docs/DATA_FORMAT.md](docs/DATA_FORMAT.md)
- Fictional examples: [samples/](samples/)
- **Adding a theme:** a folder `src/main/resources/themes/<type>/<name>/` with `document.html` and `style.css`,
  plus the line `<type>/<name>` in `themes/index.txt` (ADR-0027).

## Settings
`src/main/resources/application.yml`, or override any of these on the command line:

| property | default | meaning |
|----------|---------|---------|
| `pdfgen.data-dir` | `data` | documents + assets |
| `pdfgen.output-dir` | `output` | where `render` writes PDFs |
| `pdfgen.system-font-dir` | `C:/Windows/Fonts` | Calibri/Arial source; empty = bundled fonts only |
| `pdfgen.preferred-port` | `8080` | viewer port if free, otherwise a free port is chosen (always bound to 127.0.0.1) |

## Development
- `mvn verify` builds everything and runs all tests: Java (unit, integration, end-to-end PDF, architecture rules)
  and the editor's Vitest tests. The first build downloads Node into `frontend\node`.
- Editor UI: `frontend/` (React + TypeScript + Vite). For UI work run the app, then `cd frontend && npm run dev`
  (hot reload on http://localhost:5173, API proxied to port 8080).
  Quick Java-only build: `mvn package -Dskip.npm -Dskip.installnodenpm`.
- Architecture and conventions: [CLAUDE.md](CLAUDE.md). Plan: [docs/PLAN.md](docs/PLAN.md). Decisions: [docs/adr/](docs/adr/).
- A theme is `src/main/resources/themes/<type>/<name>/document.html` + `style.css`
  (Thymeleaf + CSS 2.1 / paged media, rendered by OpenHTMLtoPDF). Theme rules:
  - use `th:text` (escaped) for all document text;
  - keep text in normal flow: no `position: absolute/fixed` or `float` on text, and tables for columns
    ([ADR-0020](docs/adr/0020-themes-keep-text-in-flow.md)). Otherwise the PDF's text order breaks for ATS;
  - fonts: `Calibri, Carlito` / `Arial, Carlito`, icons `"FA Solid"` / `"FA Brands"`.

## Third-party assets
See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
