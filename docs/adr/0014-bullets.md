# ADR-0014: Bullets are a plain string or `{id, text}`

- Status: Accepted

## Decision
A bullet is either `"text"` or `{id, text}`; the mapper normalizes both into `Bullet(Optional<String> id, String text)`.
Only bullets with an `id` can be targeted by a variant.

## Rationale
Typical CV tailoring is removing, reordering or rewording one or two bullets. Replacing the whole
list would duplicate content. Authors add ids only where a variant needs them, so the base stays terse.

## Alternatives considered
- **Always plain strings + replace the whole list**: still available via `set: {fields: {bullets: [...]}}`.

## Consequences
"Bullet not found" errors tell the author to give that bullet an `id` in the base.
