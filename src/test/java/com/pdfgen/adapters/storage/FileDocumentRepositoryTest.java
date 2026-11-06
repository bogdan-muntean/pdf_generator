package com.pdfgen.adapters.storage;

import com.pdfgen.application.DocumentLoadException;
import com.pdfgen.application.DocumentNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileDocumentRepositoryTest {

    @TempDir
    Path dataDir;

    private FileDocumentRepository repository() {
        return new FileDocumentRepository(dataDir);
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(dataDir.resolve(name), content);
    }

    @Test
    void listsYamlDocumentsSorted() throws IOException {
        write("b.yaml", "a: 1");
        write("a.yaml", "a: 1");
        write("notes.txt", "ignored");
        assertThat(repository().listIds()).containsExactly("a", "b");
    }

    @Test
    void keepsDatesNumbersAndYesNoAsText() throws IOException {
        write("doc.yaml", """
                dates: 2023-07
                full: 2023-07-01
                version: 1.0
                count: 42
                answer: no
                country: NO
                flag: true
                empty:
                """);
        Map<String, Object> tree = repository().loadTree("doc");
        assertThat(tree).containsEntry("dates", "2023-07")
                .containsEntry("full", "2023-07-01")
                .containsEntry("version", "1.0")
                .containsEntry("count", "42")
                .containsEntry("answer", "no")
                .containsEntry("country", "NO")
                .containsEntry("flag", true)
                .doesNotContainKey("empty");
    }

    @Test
    void rejectsDuplicateKeysWithFileNameAndLine() throws IOException {
        write("dup.yaml", "title: A\ntitle: B\n");
        assertThatThrownBy(() -> repository().loadTree("dup"))
                .isInstanceOf(DocumentLoadException.class)
                .hasMessageContaining("dup.yaml")
                .hasMessageContaining("duplicate key");
    }

    @Test
    void rejectsAliasBombs() throws IOException {
        StringBuilder yaml = new StringBuilder("a: &a [x, x]\n");
        for (char c = 'b'; c <= 'l'; c++) {
            char previous = (char) (c - 1);
            yaml.append(c).append(": &").append(c)
                    .append(" [*").append(previous).append(", *").append(previous).append("]\n");
        }
        write("bomb.yaml", yaml.toString());
        assertThatThrownBy(() -> repository().loadTree("bomb")).isInstanceOf(DocumentLoadException.class);
    }

    @Test
    void refusesUnsafeTags() throws IOException {
        write("evil.yaml", "x: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[]]]\n");
        assertThatThrownBy(() -> repository().loadTree("evil")).isInstanceOf(DocumentLoadException.class);
    }

    @Test
    void requiresMapAtTopLevel() throws IOException {
        write("list.yaml", "- a\n- b\n");
        assertThatThrownBy(() -> repository().loadTree("list")).hasMessageContaining("YAML map at the top level");
    }

    @Test
    void unknownOrUnsafeIdsAreNotFound() {
        assertThatThrownBy(() -> repository().loadTree("missing")).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> repository().loadTree("../etc/passwd")).isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void loadsAssetsOnlyInsideTheDataDirectory(@TempDir Path outside) throws IOException {
        Files.createDirectories(dataDir.resolve("assets"));
        Files.write(dataDir.resolve("assets/photo.png"), new byte[]{1, 2, 3});
        Files.write(outside.resolve("secret.txt"), new byte[]{9});

        assertThat(repository().load("assets/photo.png")).containsExactly(1, 2, 3);
        String escape = dataDir.relativize(outside.resolve("secret.txt")).toString();
        assertThatThrownBy(() -> repository().load(escape))
                .isInstanceOf(DocumentLoadException.class).hasMessageContaining("outside the data directory");
        assertThatThrownBy(() -> repository().load("assets/none.png"))
                .isInstanceOf(DocumentLoadException.class).hasMessageContaining("Cannot read asset");
    }
}
