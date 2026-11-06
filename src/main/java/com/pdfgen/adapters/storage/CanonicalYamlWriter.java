package com.pdfgen.adapters.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes a raw document tree as tidy, stable YAML (ADR-0022): block style, short leaf maps/lists in flow style
 * ({@code {id: x, type: phone}}), multi-line text as literal blocks, strings quoted whenever plain style could
 * be misread by any YAML parser. {@code load(write(tree)) == tree} is tested.
 * Comments cannot be kept: the previous file is backed up before every save.
 */
final class CanonicalYamlWriter {

    static final String HEADER = "# Written by pdf-generator. Comments are not kept when saving from the app;\n"
            + "# previous versions are in .history/. Format: docs/DATA_FORMAT.md\n";

    private static final int MAX_FLOW_WIDTH = 100;
    private static final String INDENT = "  ";
    private static final Pattern SIMPLE_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");
    private static final Pattern SPECIAL_WORD =
            Pattern.compile("(?i)y|n|yes|no|true|false|on|off|null|~|=|<<");
    private static final String INDICATORS = "-?:,[]{}#&*!|>'\"%@`";
    private static final String FLOW_UNSAFE = ",[]{}:?#&*!|>'\"%@`";

    String write(Map<String, Object> tree) {
        StringBuilder out = new StringBuilder(HEADER);
        for (String line : mapLines(tree)) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    // ---- block style ---------------------------------------------------------------------------

    private List<String> mapLines(Map<String, Object> map) {
        List<String> lines = new ArrayList<>();
        map.forEach((key, value) -> {
            String prefix = key(key) + ":";
            if (value instanceof Map<?, ?> nested) {
                if (nested.isEmpty()) {
                    lines.add(prefix + " {}");
                } else if (flowFits(nested)) {
                    lines.add(prefix + " " + flow(nested));
                } else {
                    lines.add(prefix);
                    indent(mapLines(asMap(nested)), lines);
                }
            } else if (value instanceof List<?> list) {
                if (list.isEmpty()) {
                    lines.add(prefix + " []");
                } else if (flowFits(list)) {
                    lines.add(prefix + " " + flow(list));
                } else {
                    lines.add(prefix);
                    indent(listLines(list), lines);
                }
            } else if (value instanceof String text && isLiteralBlock(text)) {
                lines.add(prefix + " " + literalHeader(text));
                literalBody(text).forEach(line -> lines.add(INDENT + line));
            } else {
                lines.add(prefix + " " + scalar(value, false));
            }
        });
        return lines;
    }

    private List<String> listLines(List<?> list) {
        List<String> lines = new ArrayList<>();
        for (Object element : list) {
            if (element instanceof Map<?, ?> map && !map.isEmpty() && !flowFits(map)) {
                List<String> nested = mapLines(asMap(map));
                lines.add("- " + nested.getFirst());
                nested.subList(1, nested.size()).forEach(line -> lines.add(INDENT + line));
            } else if (element instanceof Map<?, ?> || element instanceof List<?>) {
                lines.add("- " + flow(element));
            } else if (element instanceof String text && isLiteralBlock(text)) {
                lines.add("- " + literalHeader(text));
                literalBody(text).forEach(line -> lines.add(INDENT + line));
            } else {
                lines.add("- " + scalar(element, false));
            }
        }
        return lines;
    }

    private static void indent(List<String> source, List<String> target) {
        source.forEach(line -> target.add(INDENT + line));
    }

    // ---- flow style ----------------------------------------------------------------------------

    private boolean flowFits(Object node) {
        if (!isFlowable(node)) {
            return false;
        }
        return flow(node).length() <= MAX_FLOW_WIDTH;
    }

    /** Flow style only for leaves: scalars, or maps/lists whose values are scalars or lists of scalars. */
    private static boolean isFlowable(Object node) {
        if (node instanceof Map<?, ?> map) {
            return map.values().stream().allMatch(v -> isSingleLineScalar(v)
                    || v instanceof List<?> list && list.stream().allMatch(CanonicalYamlWriter::isSingleLineScalar));
        }
        if (node instanceof List<?> list) {
            return list.stream().allMatch(CanonicalYamlWriter::isSingleLineScalar);
        }
        return isSingleLineScalar(node);
    }

    private static boolean isSingleLineScalar(Object value) {
        return value instanceof Boolean || value instanceof String text && text.indexOf('\n') < 0;
    }

    private String flow(Object node) {
        if (node instanceof Map<?, ?> map) {
            List<String> parts = new ArrayList<>();
            map.forEach((key, value) -> parts.add(key(String.valueOf(key)) + ": " + flow(value)));
            return "{" + String.join(", ", parts) + "}";
        }
        if (node instanceof List<?> list) {
            return "[" + String.join(", ", list.stream().map(this::flow).toList()) + "]";
        }
        return scalar(node, true);
    }

    // ---- scalars -------------------------------------------------------------------------------

    private static String key(String key) {
        return SIMPLE_KEY.matcher(key).matches() ? key : doubleQuoted(key);
    }

    static String scalar(Object value, boolean inFlow) {
        if (value instanceof Boolean bool) {
            return bool.toString();
        }
        String text = String.valueOf(value);
        return isPlainSafe(text, inFlow) ? text : doubleQuoted(text);
    }

    /** Conservative: plain only if no YAML parser (1.1 or 1.2, any resolver) could read it differently. */
    static boolean isPlainSafe(String text, boolean inFlow) {
        if (text.isEmpty() || !text.strip().equals(text)) {
            return false;
        }
        if (INDICATORS.indexOf(text.charAt(0)) >= 0 || Character.isDigit(text.charAt(0))
                || text.charAt(0) == '+' || text.charAt(0) == '.') {
            return false;
        }
        if (SPECIAL_WORD.matcher(text).matches() || text.contains(": ") || text.contains(" #")
                || text.endsWith(":")) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '\u0085' || c == ' ' || c == ' ' || c == '﻿') {
                return false;
            }
            // Inside {...}/[...] YAML 1.1 parsers (SnakeYAML) also stop at ':' or '?' mid-word: quote those too.
            if (inFlow && FLOW_UNSAFE.indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }

    static String doubleQuoted(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20 || c == 0x7f || c == '\u0085' || c == ' ' || c == ' ' || c == '﻿') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** Multi-line text as {@code |} block, unless its shape needs escaping (leading spaces, special chars). */
    private static boolean isLiteralBlock(String text) {
        if (text.indexOf('\n') < 0 || text.startsWith(" ") || text.startsWith("\n")) {
            return false;
        }
        for (String line : text.split("\n", -1)) {
            if (!line.strip().equals(line) && !line.isEmpty() || line.chars().anyMatch(c -> c < 0x20 && c != '\t')) {
                return false;
            }
        }
        return !text.endsWith("\n\n");
    }

    private static String literalHeader(String text) {
        return text.endsWith("\n") ? "|" : "|-";
    }

    private static List<String> literalBody(String text) {
        String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        return List.of(body.split("\n", -1));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
