# ADR-0007: Plain files in a configurable data directory

- Status: Accepted

## Decision
A `DocumentRepository` port with a file adapter over `data/`. All paths are normalized and confined
to the data root; path traversal is rejected.

## Rationale
Single user, a handful of documents, diff-able, no schema or migrations to maintain.

## Alternatives considered
- **SQLite / H2**: only if querying or history beyond git is ever needed.
