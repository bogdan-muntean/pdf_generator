# ADR-0006: Thymeleaf templates, normalized through jsoup W3CDom

- Status: Accepted

## Decision
Thymeleaf renders HTML; jsoup parses it into a W3C DOM that is handed to OpenHTMLtoPDF.

## Rationale
Escapes by default (`th:text`), which the security principle requires. Natural templates open in a browser.
Fragments map 1:1 to section kinds. jsoup removes XHTML well-formedness failures.

## Alternatives considered
- **FreeMarker**: escaping must be configured; noisier syntax in HTML.
- **Mustache / Pebble**: weaker fragment/Spring story, smaller community.

## Consequences
A theme is `templates/<docType>/<theme>.html` + a CSS file.
