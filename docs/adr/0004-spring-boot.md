# ADR-0004: Spring Boot 4.x + picocli

- Status: Accepted

## Decision
Spring Boot 4.x (3.5 is out of OSS support) with spring-web + Thymeleaf; picocli for CLI parsing.
CLI mode runs with `web-application-type=none`.

## Rationale
The owner knows Spring (its DI is similar to Symfony's). Spring gives config, template integration,
test slices and a fat jar for free. The hexagonal rule keeps Spring in `adapters`/bootstrap only,
enforced by an ArchUnit test.

## Alternatives considered
- **Javalin + picocli**: lighter and faster startup, but more hand-wiring and less familiar.
- **Plain Java**: reinvents config and serving.

## Consequences
About 2 s startup (acceptable).
