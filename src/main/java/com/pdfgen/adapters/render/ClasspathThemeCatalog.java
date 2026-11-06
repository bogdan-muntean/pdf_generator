package com.pdfgen.adapters.render;

import com.pdfgen.application.ThemeCatalog;
import com.pdfgen.domain.model.DocumentType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Themes listed in {@code classpath:themes/index.txt}, one {@code <type>/<name>} per line, {@code #} for comments
 * (ADR-0027). An explicit index works the same from the IDE, {@code target/classes} and the jar; a test checks it
 * against the theme folders.
 */
public final class ClasspathThemeCatalog implements ThemeCatalog {

    static final String INDEX = "/themes/index.txt";

    private final Map<String, List<String>> themesByType;

    public ClasspathThemeCatalog() {
        this.themesByType = read();
    }

    @Override
    public List<String> themes(DocumentType type) {
        return themesByType.getOrDefault(type.key(), List.of());
    }

    /** All index entries as {@code type/name}, for the consistency test. */
    List<String> entries() {
        List<String> entries = new ArrayList<>();
        themesByType.forEach((type, names) -> names.forEach(name -> entries.add(type + "/" + name)));
        return entries;
    }

    private static Map<String, List<String>> read() {
        Map<String, List<String>> themes = new TreeMap<>();
        try (InputStream in = ClasspathThemeCatalog.class.getResourceAsStream(INDEX)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + INDEX + " on the classpath");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String entry = line.replaceAll("#.*", "").strip();
                if (entry.isEmpty()) {
                    continue;
                }
                int slash = entry.indexOf('/');
                if (slash <= 0 || slash == entry.length() - 1) {
                    throw new IllegalStateException(INDEX + ": expected <type>/<name>, found '" + entry + "'");
                }
                themes.computeIfAbsent(entry.substring(0, slash), key -> new ArrayList<>()).add(entry.substring(slash + 1));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        themes.replaceAll((type, names) -> names.stream().sorted().toList());
        return Map.copyOf(themes);
    }
}
