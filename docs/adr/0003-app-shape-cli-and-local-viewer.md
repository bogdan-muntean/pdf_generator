# ADR-0003: One jar, two modes: CLI `render` + local web viewer `serve`

- Status: Accepted

## Decision
- CLI: `render` a base or a variant to PDF (scriptable, e.g. render all variants).
- Web: a vanilla HTML/JS page on `127.0.0.1` that lists documents, previews the PDF in an iframe and
  auto-refreshes when files in `data/` change. It is a **viewer, not an editor**: the owner edits YAML in VS Code.

## Rationale
Live preview speeds up layout work; no Node toolchain inside the Maven build; about 200 lines of JS.

## Implementation note
Live refresh polls `GET /api/changes` every 1.5 s; the token is the newest modification time under `data/`.
That is simpler than a `WatchService` (no background thread or lifecycle) and fast enough for one user.

## Alternatives considered
- **CLI only**: no live preview, slow feedback.
- **JavaFX desktop**: new toolkit, more code, little benefit.
- **React/TS SPA**: only justified with a form-based editor; revisit in a new ADR if needed.
