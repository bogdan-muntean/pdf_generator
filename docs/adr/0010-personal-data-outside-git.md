# ADR-0010: Real personal data is git-ignored; fictional samples for tests

- Status: Accepted (a default: the owner may override it, since it concerns personal data)

## Decision
`data/` is git-ignored (it can be its own private git repo). `samples/` holds a fictional dataset
used by tests, docs and demos.

## Rationale
The code repo may become a public portfolio piece, and contact details and the photo are personal data.
Tests must not depend on personal files.
