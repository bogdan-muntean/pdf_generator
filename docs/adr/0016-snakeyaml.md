# ADR-0016: SnakeYAML directly, with strict typing (supersedes the parser choice in ADR-0005)

- Status: Accepted

## Question
ADR-0005 named Jackson YAML. Spring Boot 4 ships Jackson 3, and the overlay engine needs a plain Map/List tree anyway.

## Decision
Use SnakeYAML 2.x (managed by Spring Boot) in the storage adapter only:
- `SafeConstructor`: only plain maps, lists and scalars, never arbitrary classes;
- duplicate keys are an error; low alias limit; code-point limit;
- the resolver types only `true/false` and null. `2023-07`, `1.0` and `yes/no/on/off` stay text
  (this avoids the "Norway problem").

## Alternatives considered
- **Jackson 3 YAML**: an extra conversion layer and Jackson 3 churn for no benefit.

## Consequences
`StrictYaml` and its tests own all typing rules (dates, numbers, `no`, duplicates, alias bombs, unsafe tags).
