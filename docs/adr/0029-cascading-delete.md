# ADR-0029: Deleting a document together with its variants

- Status: Accepted (owner request; Q53 answered by the lead architect). Supersedes ADR-0022 Q32 ("base documents are
  never deleted from the app") and the "delete refused while variants extend it" part of ADR-0025. That refusal remains
  the behaviour without `cascade`.

## Request
"Make a delete for the first layer of a CV / cover letter; when it is deleted, delete layers 2 and 3 in cascade."
Then: "if the first layer is deleted, all of them are deleted in cascade."

## Decision (Q53)
1. **Endpoint.** `DELETE /api/documents/{id}?cascade=true` deletes the document and **every variant built on it at
   any level**. It works the same for bases and variants. Without `cascade`, a document with variants is refused and
   the message names them. A document without variants can always be deleted.
2. **Confirmed list.** The UI computes the subtree from the list it already has, shows it in a dialog (every document
   with its type; the button reads "Delete N documents") and sends it in `X-Pdfgen-Expected`. The server recomputes
   the subtree (`DocumentService.subtreeDeepestFirst`). If the two differ, for example because a variant was created
   meanwhile, it answers **409** and deletes nothing.
3. **Recoverable.** Before deleting, a ZIP of the files is written to `data/.history/delete-<id>-<time>.zip` in the
   backup format (ADR-0028); the newest 20 are kept. It can be restored with *Restore…*, but only as a full replace,
   which removes documents created since. Taking the files out of the ZIP by hand also works. Each file also gets
   its usual `.history/<id>/` copy.
4. **All or nothing.** Deepest variants are deleted first. If one delete fails, the files already deleted are written
   back and the error says nothing was deleted.
5. **Unreadable files.** If any other data file cannot be parsed, the delete is refused and that file is named: it
   might extend the document, so the subtree is not known for sure. The unreadable file itself can be deleted.
6. **Photos are not deleted**: they may be shared, or put there by hand.
7. **Request protection.** `X-Pdfgen-Action: delete`, plus the strict Origin check for non-GET requests (ADR-0024).
8. **UI.** A *delete* button on every node, bases included, and on *Needs fixing* entries. If the open document is
   deleted, the editor closes it.

## Alternatives rejected
- Stopping at the first failure and reporting it: leaves a partial tree with broken variants.
- A server endpoint that returns the subtree: not needed, since the summaries have `parentId` and the server checks
  the list anyway.
- Soft delete into a trash folder: the safety ZIP and `.history` already cover recovery.
- Deleting photos no longer referenced: YAGNI, and it risks deleting a photo the owner placed by hand.

## Consequences
- `DocumentService.deleteVariant` became `delete(id, cascade, expectedIds)`, and the port gained
  `DocumentRepository.deleteAll(ids, label)`.
- Tests: `CascadeDeleteTest` covers three levels, the middle of a chain, the refusal without cascade, a stale list,
  an unreadable file and photos left alone. `ZipDataArchiveTest` covers the rollback on an injected failure and
  checks that the safety ZIP holds the deleted files. `BackupAndDeleteHttpTest` covers the headers and the 409.
  The Vitest checks the dialog and the confirmed list.
