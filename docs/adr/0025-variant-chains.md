# ADR-0025: Variant chains (base → variant → variant …)

> Update (ADR-0029): with `cascade=true` a document is deleted together with its variants; the refusal remains without it.

- Status: Accepted (owner request; question Q42 answered by the lead architect). Supersedes the "one inheritance
  level; chains are YAGNI" consequence of ADR-0005.

## Request
"Offer a third layer: from cv-en I make cv-en-fullstack, and from it a CV for one company, for example
cv-en-fullstack-luxoft."

## Decision (Q42)
A variant may `extend` another variant. The file format is unchanged (`extends: <parent id>`).

| Topic | Rule |
|-------|------|
| Depth | At most **5 variant levels** above the base. A deeper file, a cycle or a missing parent fails fast with a message naming the chain, e.g. `Variant chain has a cycle: cv-a -> cv-b -> cv-a`. |
| Semantics | A variant is applied to its **parent's resolved tree**. On save, `OverlayDiff` compares with the parent's resolved tree, so `apply(parent, diff(parent, edited)) == edited` still holds, and so does the re-check before writing. `OverlayEngine` and `OverlayDiff` are unchanged. |
| One loader | `DocumentService.loadChain(id)` walks `extends` up to the base (a loop, not recursion). `resolve`, `openForEdit`, `save`, `createVariant` and the descendant check all use it. |
| Validation | Each level is validated from the base down (`'cv-en' + variant 'cv-en-fullstack' + variant '…-luxoft'`), so an error points at the first file that breaks. |
| Version token | The fingerprints of **every file** of the chain. A change to any ancestor on disk gives 409 and *Reload*: the diff base changed. |
| Naming | `baseId` → **`parentId`** (and `baseTree` → `parentTree`) in `EditableDocument`, `DocumentSummary`, the API and the editor. *changed in this variant* / *revert* compare with the parent and their tooltips name it. |
| Save | Warns about every descendant, at any depth, that no longer resolves. |
| Delete | Refused while other variants extend the document; the message lists them. Create refuses a parent already at the depth limit. |
| Sidebar | A tree indented by depth; *+ variant* on every document. A document whose chain is broken appears under *Needs fixing* with its error. |

## Alternatives rejected
- Keeping one level and copying operations into company variants: role-level fixes would have to be copied by hand.
- Several parents (mixins): merge order and conflicts would be hard to read; the need is linear.
- Cascading delete: irreversible in bulk and easy to trigger by accident.
- Cycle detection without a depth limit: viable, but the limit gives a clearer error for a runaway file.
- Flattening on save (each variant stores its diff against the root): middle-level changes would stop flowing down.

## Consequences
- `resolve` loads and validates one more file per level (at most 6 small YAML files: negligible).
- Saves are refused a little more often after an ancestor changed on disk (*Reload* is offered).
- If an ancestor removes an element a child targets, the child stops resolving; the save warning reports it.
- Tests: `samples/cv-sample-qa-acme.yaml` (3 levels) rendered end to end; cycle, depth, missing parent,
  delete-with-children; diff invariant at level 3; base change flowing to level 3; grandchild warning.
