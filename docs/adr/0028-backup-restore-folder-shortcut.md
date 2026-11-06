# ADR-0028: Backup, restore and a shortcut in the project folder

- Status: Accepted (owner request; questions Q50–Q52 answered by the lead architect). Extends ADR-0021 (launcher)
  and ADR-0024 (request protection).

## Request
"Add the shortcut for starting the app in the folder. Also make a backup function for the data, saved wherever the
user wants on the computer, and a function to import the backup."

## Decisions

| # | Question | Decision | Alternatives rejected |
|---|----------|----------|-----------------------|
| Q50 | Shortcut in the folder | `create-shortcut.ps1` also writes `PDF Generator.lnk` into the project folder, through the same loop as the Desktop and Start Menu shortcuts. It has the same target, working directory and icon. `-NoFolderShortcut` skips it. `/PDF Generator.lnk` is git-ignored because it holds machine-specific paths. | A committed `.lnk` (absolute paths, binary); a `.bat` in the folder (`start.bat` already exists and shows a console). |
| Q51 | Backup format and export | **One ZIP**. `manifest.json` comes first: format `pdf-generator-backup`, version 1, creation time, and path + size + SHA-256 of every file. Then come the files, sorted: `<id>.yaml` at the top and `assets/**`. `.history`, temp files and anything else are excluded. Export: `GET /api/backup` with `X-Pdfgen-Action: backup` (a plain link or another site cannot trigger it). The UI asks **where to save first** with `showSaveFilePicker` (Chrome/Edge: any folder on the computer), then writes the file. Other browsers fall back to a normal download. Port `DataArchive` (application) is implemented by the adapter `ZipDataArchive` (storage) and used through `BackupService`. | Copying the folder with a server-side folder dialog (Swing dialog from a web app, hidden behind the browser); a git push (needs a remote, not a "file I can keep"); including `.history` (bigger, and old versions are not "the data"). |
| Q52 | Restore | `POST /api/backup/restore`, raw `application/zip` body, `X-Pdfgen-Action: restore`; at most 100 MB, read to a temp file. **Everything is validated before any change:** `manifest.json` first and from this app; every file listed with a matching checksum and nothing unlisted; entry names only `<id>.yaml` or `assets/...`, with no `..`, absolute paths, drive letters, backslashes, control characters, Windows reserved names or trailing dots/spaces, and no case-insensitive duplicates; at most 1000 files and 200 MB actually unpacked; every YAML parses to a map. A broken variant chain is **not** an error: it is restored and reported as a warning, so nothing in the backup is lost. Then a **safety backup** of the current data is written to `data/.history/restore-<time>.zip` (newest 5 kept). The current documents and `assets/` are moved aside and the backup's are moved in; any failure moves the originals back. `.history` is never touched. The response is `{restored, removed, warnings, safetyBackup}`. The UI asks first with a dialog that says all documents and photos are replaced, then reloads the list. | Merging (which file wins? deferred until there is a need); restoring without a safety copy; trusting the ZIP (zip-slip, zip bombs). |

## Consequences
- Tests: `ZipDataArchiveTest` covers the layout, round trip, safety backup and its pruning, the ten kinds of invalid
  backup (data unchanged) and the name rules. `BackupAndDeleteHttpTest` covers the headers, the cross-site Origin
  check and the HTTP round trip.
- Limitation: a save made while a restore is running is not locked out (single user, local app); the restore itself
  is serialized.
- Follow-up (only on need): a merge import that adds only the missing documents.
