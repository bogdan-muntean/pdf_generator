# ADR-0005: YAML data + custom id-based overlay operations for variants

- Status: Accepted (refined by ADR-0013, ADR-0014, ADR-0015; the one-level rule is superseded by ADR-0025: variants can extend variants)

## Decision
Documents are YAML (parsed by Jackson YAML in an adapter). A **variant** `extends` a base and lists
operations applied in order; each produces a new document:

| op | purpose |
|----|---------|
| `set`    | shallow-replace fields of a target (content edits, section title rename, person fields) |
| `add`    | insert a section or item (`after` / `before` an id; default: at the end) |
| `remove` | delete a section / item / bullet |
| `order`  | reorder sections or items by id; unlisted ids follow in base order (warning logged) |

Targets are paths of ids: `sectionId[/itemId[/bulletId]]`.

## Rationale
- YAML: comments, multi-line text (`|`), clean diffs. JSON is unpleasant to write by hand.

## Alternatives considered
- **RFC 6902 JSON Patch**: index-based paths, so reordering the base silently breaks variants.
- **RFC 7386 Merge Patch**: replaces arrays wholesale, so it cannot remove or reorder a single item.
- **Full copy per variant**: duplication; fixes in the base don't propagate.

## Consequences
Unknown or duplicate ids fail fast with file, operation index and path. One inheritance level
(variant extends base) in the MVP; variant chains are YAGNI.
