package com.pdfgen.adapters.storage;

import com.pdfgen.application.DataArchive.InvalidBackupException;
import com.pdfgen.application.DataArchive.Restored;
import com.pdfgen.application.DocumentLoadException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Backup ZIP format, restore validation and swap (ADR-0028), and the all-or-nothing delete (ADR-0029). */
class ZipDataArchiveTest {

    @TempDir
    Path data;
    @TempDir
    Path elsewhere;

    private ZipDataArchive archive;

    @BeforeEach
    void setUp() throws IOException {
        for (String name : List.of("cv-sample.yaml", "cv-sample-qa.yaml", "cv-sample-qa-acme.yaml")) {
            Files.copy(Path.of("samples", name), data.resolve(name));
        }
        Files.createDirectories(data.resolve("assets"));
        Files.write(data.resolve("assets/me.jpg"), new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2, 3});
        Files.createDirectories(data.resolve(".history/cv-sample"));
        Files.writeString(data.resolve(".history/cv-sample/old.yaml"), "old: true");
        Files.writeString(data.resolve("notes.txt"), "not a document");
        archive = new ZipDataArchive(data);
    }

    private Path exportTo(String name) throws IOException {
        Path zip = elsewhere.resolve(name);
        try (OutputStream out = Files.newOutputStream(zip)) {
            archive.export(out);
        }
        return zip;
    }

    /** A ZIP with the given entries, in order (null content = directory). */
    private Path zip(String name, Map<String, byte[]> entries) throws IOException {
        Path zip = elsewhere.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                if (entry.getValue() != null) {
                    out.write(entry.getValue());
                }
                out.closeEntry();
            }
        }
        return zip;
    }

    /** A valid backup of {@code files}, written with the real writer. */
    private Path backupOf(String name, SortedMap<String, byte[]> files) throws IOException {
        Path zip = elsewhere.resolve(name);
        ZipDataArchive.writeFile(zip, files);
        return zip;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private List<String> dataFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(data)) {
            return walk.filter(Files::isRegularFile).map(p -> data.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> !p.startsWith(".history/")).sorted().toList();
        }
    }

    @Test
    void exportHasTheManifestFirstThenSortedDocumentsAndAssetsButNoHistory() throws IOException {
        Path zip = exportTo("backup.zip");
        assertThat(ZipDataArchive.entryNames(zip)).containsExactly("manifest.json", "assets/me.jpg",
                "cv-sample-qa-acme.yaml", "cv-sample-qa.yaml", "cv-sample.yaml");
        SortedMap<String, byte[]> read = archive.readAndValidate(zip);
        assertThat(read.get("cv-sample.yaml")).isEqualTo(Files.readAllBytes(data.resolve("cv-sample.yaml")));
    }

    @Test
    void restoreReplacesDocumentsAndAssetsKeepsHistoryAndWritesASafetyBackup() throws IOException {
        Path backup = exportTo("backup.zip");
        // after the backup: one document edited, one added, one photo added
        Files.writeString(data.resolve("cv-sample.yaml"), "changed: true");
        Files.writeString(data.resolve("cv-new.yaml"), "meta: {type: cv}");
        Files.write(data.resolve("assets/other.png"), new byte[] {1});

        Restored restored = archive.restore(backup);

        assertThat(restored.restored()).containsExactly("cv-sample", "cv-sample-qa", "cv-sample-qa-acme");
        assertThat(restored.removed()).containsExactly("cv-new");
        assertThat(Files.readString(data.resolve("cv-sample.yaml")))
                .isEqualTo(Files.readString(Path.of("samples/cv-sample.yaml")));
        assertThat(dataFiles()).containsExactly("assets/me.jpg", "cv-sample-qa-acme.yaml", "cv-sample-qa.yaml",
                "cv-sample.yaml", "notes.txt");
        assertThat(data.resolve(".history/cv-sample/old.yaml")).exists();
        // the safety backup holds the data as it was just before, and is itself restorable
        Path safety = data.resolve(restored.safetyBackup());
        assertThat(restored.safetyBackup()).startsWith(".history/restore-").endsWith(".zip");
        assertThat(archive.readAndValidate(safety)).containsKeys("cv-new.yaml", "assets/other.png");
        try (Stream<Path> leftovers = Files.list(data.resolve(".history"))) {
            assertThat(leftovers.map(p -> p.getFileName().toString()))
                    .noneMatch(n -> n.startsWith("restore-staging") || n.startsWith("restore-old"));
        }
    }

    @Test
    void keepsOnlyTheNewestSafetyBackups() throws IOException {
        Path backup = exportTo("backup.zip");
        for (int i = 0; i < ZipDataArchive.SAFETY_RESTORE_KEEP + 2; i++) {
            archive.restore(backup);
        }
        try (Stream<Path> files = Files.list(data.resolve(".history"))) {
            assertThat(files.filter(p -> p.getFileName().toString().startsWith("restore-"))).hasSize(
                    ZipDataArchive.SAFETY_RESTORE_KEEP);
        }
    }

    @Test
    void rejectsInvalidBackupsWithoutChangingAnything() throws IOException {
        List<String> before = dataFiles();
        String original = Files.readString(data.resolve("cv-sample.yaml"));

        SortedMap<String, byte[]> good = new TreeMap<>(Map.of("cv-a.yaml", bytes("meta: {type: cv}")));
        Path valid = backupOf("valid.zip", good);
        assertThat(archive.readAndValidate(valid)).containsOnlyKeys("cv-a.yaml");

        Map<String, String> cases = new java.util.LinkedHashMap<>();
        cases.put("not a zip", "not a ZIP");
        cases.put("no manifest", "first file must be manifest.json");
        cases.put("bad checksum", "damaged");
        cases.put("unlisted file", "do not match");
        cases.put("traversal", "unsafe file name");
        cases.put("reserved name", "unsafe file name");
        cases.put("case duplicate", "appears twice");
        cases.put("unexpected file", "unexpected file");
        cases.put("bad yaml", "cannot be parsed");
        cases.put("other format", "not from PDF Generator");

        Map<String, Path> files = new java.util.LinkedHashMap<>();
        Path notZip = elsewhere.resolve("not.zip");
        Files.writeString(notZip, "hello");
        files.put("not a zip", notZip);
        files.put("no manifest", zip("nomanifest.zip", Map.of("cv-a.yaml", bytes("a: 1"))));
        String manifestA = "{\"format\": \"pdf-generator-backup\", \"version\": 1, \"files\": ["
                + "{\"path\": \"cv-a.yaml\", \"size\": 4, \"sha256\": \"" + "0".repeat(64) + "\"}]}";
        files.put("bad checksum", zip("checksum.zip", ordered("manifest.json", bytes(manifestA), "cv-a.yaml", bytes("a: 1"))));
        String manifestEmpty = "{\"format\": \"pdf-generator-backup\", \"version\": 1, \"files\": []}";
        files.put("unlisted file", zip("unlisted.zip", ordered("manifest.json", bytes(manifestEmpty), "cv-a.yaml", bytes("a: 1"))));
        files.put("traversal", zip("traversal.zip", ordered("manifest.json", bytes(manifestEmpty), "../evil.yaml", bytes("a: 1"))));
        files.put("reserved name", zip("reserved.zip", ordered("manifest.json", bytes(manifestEmpty), "assets/con.jpg", bytes("x"))));
        files.put("case duplicate", zip("dup.zip", ordered("manifest.json", bytes(manifestEmpty), "cv-a.yaml", bytes("a: 1"),
                "CV-A.yaml", bytes("a: 2"))));
        files.put("unexpected file", zip("unexpected.zip", ordered("manifest.json", bytes(manifestEmpty), "run/x.txt", bytes("x"))));
        files.put("bad yaml", backupOf("badyaml.zip", new TreeMap<>(Map.of("cv-a.yaml", bytes("a: [unclosed")))));
        files.put("other format", zip("other.zip", ordered("manifest.json", bytes("{\"format\": \"something\"}"))));

        for (Map.Entry<String, Path> file : files.entrySet()) {
            assertThatThrownBy(() -> archive.restore(file.getValue())).as(file.getKey())
                    .isInstanceOf(InvalidBackupException.class)
                    .hasMessageContaining(cases.get(file.getKey()));
        }
        assertThat(dataFiles()).isEqualTo(before);
        assertThat(Files.readString(data.resolve("cv-sample.yaml"))).isEqualTo(original);
        assertThat(data.resolve(".history")).isDirectoryNotContaining("glob:**/restore-*");
    }

    @Test
    void entryNameRules() {
        for (String ok : List.of("cv-en.yaml", "assets/me.jpg", "assets/sub/photo 1.png", "assets")) {
            ZipDataArchive.requireSafeName(ok);
        }
        for (String bad : List.of("/cv.yaml", "C:/cv.yaml", "assets\\x.jpg", "assets/../cv.yaml", "assets//x.jpg",
                "assets/nul", "assets/LPT1.txt", "assets/x.", "assets/x ", "assets/a\u0001b.jpg", ".history/x.yaml",
                "cv.yml", "dir/cv.yaml")) {
            assertThatThrownBy(() -> ZipDataArchive.requireSafeName(bad)).as(bad)
                    .isInstanceOf(InvalidBackupException.class);
        }
    }

    @Test
    void deleteAllIsAllOrNothingAndKeepsARestorableCopy() throws IOException {
        FileDocumentRepository repository = new FileDocumentRepository(data);
        String acme = Files.readString(data.resolve("cv-sample-qa-acme.yaml"));
        int[] calls = {0};
        repository.useDeleter(file -> {
            if (++calls[0] == 2) {
                throw new IOException("file is locked");
            }
            Files.delete(file);
        });
        assertThatThrownBy(() -> repository.deleteAll(List.of("cv-sample-qa-acme", "cv-sample-qa"), "cv-sample-qa"))
                .isInstanceOf(DocumentLoadException.class).hasMessageContaining("nothing was deleted");
        assertThat(Files.readString(data.resolve("cv-sample-qa-acme.yaml"))).isEqualTo(acme); // put back
        assertThat(data.resolve("cv-sample-qa.yaml")).exists();

        repository.useDeleter(Files::delete);
        String safety = repository.deleteAll(List.of("cv-sample-qa-acme", "cv-sample-qa", "cv-sample"), "cv-sample");
        assertThat(dataFiles()).containsExactly("assets/me.jpg", "notes.txt"); // photos are not deleted
        assertThat(safety).startsWith(".history/delete-cv-sample-");
        SortedMap<String, byte[]> copy = archive.readAndValidate(data.resolve(safety));
        assertThat(copy).containsOnlyKeys("cv-sample.yaml", "cv-sample-qa.yaml", "cv-sample-qa-acme.yaml");
        assertThat(new String(copy.get("cv-sample-qa-acme.yaml"), StandardCharsets.UTF_8)).isEqualTo(acme);
    }

    private static Map<String, byte[]> ordered(Object... nameThenContent) {
        Map<String, byte[]> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < nameThenContent.length; i += 2) {
            map.put((String) nameThenContent[i], (byte[]) nameThenContent[i + 1]);
        }
        return map;
    }
}
