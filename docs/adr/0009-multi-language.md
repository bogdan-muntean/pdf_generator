# ADR-0009: A language is a separate base document, not field translations

- Status: Accepted

## Decision
Documents carry `lang` (`en`, `ro`). The Romanian CV is its own base file reusing the same ids.
`lang` drives the HTML `lang` attribute and theme labels. Diacritics (ă â î ș ț) must render, and this is tested.

## Alternatives considered
- **Per-field translation maps**: double the authoring complexity; the RO CV is written separately anyway.
