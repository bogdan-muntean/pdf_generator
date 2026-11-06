package com.pdfgen.adapters.render;

import com.pdfgen.domain.model.DocumentType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** themes/index.txt must list exactly the theme folders, each complete (ADR-0027). */
class ThemeCatalogTest {

    private static final Path THEMES = Path.of("src/main/resources/themes");

    @Test
    void listsTheThemesPerType() {
        ClasspathThemeCatalog catalog = new ClasspathThemeCatalog();
        assertThat(catalog.themes(DocumentType.CV)).containsExactly("classic");
        assertThat(catalog.themes(DocumentType.COVER_LETTER)).containsExactly("classic", "modern");
    }

    @Test
    void theIndexMatchesTheThemeFolders() throws IOException {
        List<String> folders;
        try (Stream<Path> dirs = Files.walk(THEMES, 2)) {
            folders = dirs.filter(dir -> THEMES.relativize(dir).getNameCount() == 2 && Files.isDirectory(dir))
                    .map(dir -> THEMES.relativize(dir).toString().replace('\\', '/'))
                    .sorted().toList();
        }
        List<String> indexed = new ClasspathThemeCatalog().entries().stream().sorted().toList();
        assertThat(indexed).as("themes/index.txt vs folders in " + THEMES).isEqualTo(folders);
        for (String theme : indexed) {
            assertThat(THEMES.resolve(theme).resolve("document.html")).as(theme).isRegularFile();
            assertThat(THEMES.resolve(theme).resolve("style.css")).as(theme).isRegularFile();
        }
    }
}
