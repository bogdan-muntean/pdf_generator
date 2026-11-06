# ADR-0000: Record architecture decisions; delegate choices to the lead-architect agent

- Status: Accepted
- Asked by: owner (explicit instruction)

## Context
The owner does not want to be consulted on technical choices, but wants to understand afterwards
what was decided, why, and which alternatives existed.

## Decision
Technical questions are answered by the `lead-architect` agent (`.claude/agents/lead-architect.md`).
Each answer is stored as an ADR with: question, decision, rationale, alternatives, consequences.

## Consequences
`docs/adr/` is the audit trail of the project. Superseding a decision requires a new ADR.
