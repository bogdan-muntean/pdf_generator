# ADR-0015: `person` and `meta` are reserved overlay targets

- Status: Accepted (extended by ADR-0026: `letter` is a third reserved root, for cover letters)

## Decision
Besides section ids, `set` may target `person` (e.g. `jobTitle: QA ENGINEER`) and `meta`
(`theme`, `lang`, `outputName`). `type` and `extends` cannot be changed by a variant.

## Rules
- A section id of `person` or `meta` is rejected.
- Only `set` is allowed on reserved roots; `add` / `remove` / `order` on them are errors.

## Alternatives considered
- **A dedicated op per field**: redundant.
- **A `sections/<id>` prefix for every path**: verbose for no gain.
