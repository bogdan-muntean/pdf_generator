# ADR-0012: Fat jar + .bat launchers

- Status: Accepted

## Decision
`start.bat` (serve + open the browser) and `render.bat` (CLI pass-through); both build the jar if it is missing.
Developers can use `mvn spring-boot:run`.

## Alternatives considered
- **jpackage / MSI installer**: YAGNI for one machine.
- **`mvn spring-boot:run` only**: slow and awkward day to day.
