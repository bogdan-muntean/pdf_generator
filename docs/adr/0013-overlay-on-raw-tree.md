# ADR-0013: Overlay engine runs on a framework-free raw tree, then maps to typed records

- Status: Accepted

## Question
Should variant operations run on typed domain records (A), or on a raw Map/List/String tree followed
by one mapping + validation step (B)?

## Decision
**(B).** The overlay engine works on an immutable `java.util` Map/List/String tree inside `domain`.
A single mapper then turns the merged tree into typed sealed records and validates it.

## Rules
- Validate the base alone first, then the merged result, so a base error is never blamed on a variant.
- Each op checks its target/position ids when it is applied. Errors give file, operation index and id path.
- Copy-on-write: unmodifiable `LinkedHashMap`s and `List.copyOf`; the base is never mutated.
- List elements are addressed by their `id` key; ids are unique among siblings.
- No Jackson in `domain`: the YAML adapter only produces the raw tree.

## Rationale
The engine is document-type agnostic (cover letters get variants for free), and `set` is a generic
shallow replace. Templates and use cases still only see typed records.

## Alternatives considered
- **(A) typed operations**: compile-time checks on the ops, but N x fields of boilerplate and a rewrite per document type.

## Consequences
The mapper must be thorough (unknown fields, wrong types, missing required fields), with unit tests per kind.
