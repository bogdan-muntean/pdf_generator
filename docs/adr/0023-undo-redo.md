# ADR-0023: Undo / redo in the editor

- Status: Accepted (owner request; questions Q34–Q37 answered by the lead architect). Extends ADR-0022.

## Request
"Let me go back to the previous change, and forward again after going back. If I went back and then make a
new change, the forward changes are gone and the new change becomes the latest."

## Decisions

| # | Question | Decision | Alternatives rejected |
|---|----------|----------|-----------------------|
| Q34 | Step size | Typing in the **same text field** (same DOM node) with no pause longer than 1 s is **one step**. Every button, checkbox and select (add, remove, move, revert, discard…) is its own step. A run ends on a focus change, a pause, undo/redo or a save. | One step per keystroke (tedious); time-only merging (joins unrelated edits); explicit keys per field (same result, more code). |
| Q35 | Lifecycle | One history per opened document, in memory, last **100** steps. **Save keeps the history**: undo after save is allowed and makes the document dirty again. Opening another document or **reloading from disk clears it**, so undo can never bring back content from before an external change. **Discard** is an undoable step. | Clearing on save (loses work); keeping history across a reload from disk (undo could silently overwrite the external change). |
| Q36 | UI | **↶ Undo / ↷ Redo** buttons in the editor header (disabled when not possible), **Ctrl+Z**, **Ctrl+Y / Ctrl+Shift+Z**. These replace the browser's own undo inside the editor's text fields, so there is one predictable history instead of two competing ones. Ignored during IME composition. | Native per-field undo (unreliable with React controlled inputs, separate from the document history). |
| Q37 | Where | Pure, generic `frontend/src/history.ts` (`push` with coalescing, `undo`, `redo`, `replacePresent`), unit-tested. `App` passes `document.activeElement` and `Date.now()`. No backend change. | History in the backend (pointless: the draft lives in the browser until saved). |

## Implementation notes
- "Dirty" stays a deep comparison of the present with the saved version, so undoing back to the saved state shows "no unsaved changes".
- After Save, the stored version returned by the server (which may carry new bullet ids) replaces the current step
  without adding one. If the user kept typing during the save, their newer edits are kept.

## Consequences
After an undo the caret may jump to the end of the field. Accepted.
