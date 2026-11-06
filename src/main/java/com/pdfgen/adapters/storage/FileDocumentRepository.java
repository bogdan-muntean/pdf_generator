package com.pdfgen.adapters.storage;

import com.pdfgen.application.AssetSource;
import com.pdfgen.application.DocumentLoadException;
import com.pdfgen.application.DocumentNotFoundException;
import com.pdfgen.application.DocumentRepository;
import com.pdfgen.domain.tree.Tree;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Arrays;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Documents are {@code <dataDir>/<id>.yaml}; assets live anywhere below {@code dataDir}
 * (conventionally {@code assets/}). Nothing outside {@code dataDir} is ever read (ADR-0007).
 */
public final class FileDocumentRepository implements DocumentRepository, AssetSource {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final String EXTENSION = ".yaml";
    static final String HISTORY_DIR = ".history";
    static final int HISTORY_LIMIT = 20;
    static final String ASSETS_DIR = "assets";
    private static final Pattern SAFE_ASSET_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,59}\\.(jpg|png)");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final Path root;

    public FileDocumentRepository(Path dataDir) {
        this.root = dataDir.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    @Override
    public List<String> listIds() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(root)) {
            return files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(EXTENSION))
                    .map(name -> name.substring(0, name.length() - EXTENSION.length()))
                    .filter(id -> ID.matcher(id).matches())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new DocumentLoadException("Cannot list documents in " + root + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, Object> loadTree(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new DocumentNotFoundException(String.valueOf(id));
        }
        Path file = root.resolve(id + EXTENSION);
        if (!Files.isRegularFile(file)) {
            throw new DocumentNotFoundException(id);
        }
        Object parsed;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            parsed = StrictYaml.create().load(reader);
        } catch (YAMLException e) {
            throw new DocumentLoadException("Cannot parse " + file.getFileName() + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw new DocumentLoadException("Cannot read " + file.getFileName() + ": " + e.getMessage(), e);
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new DocumentLoadException(file.getFileName() + ": the file must contain a YAML map at the top level");
        }
        try {
            return Tree.freezeMap(map);
        } catch (IllegalArgumentException e) {
            throw new DocumentLoadException(file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(String id) {
        return id != null && ID.matcher(id).matches() && Files.isRegularFile(file(id));
    }

    @Override
    public String fingerprint(String id) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(existingFile(id)));
            return HexFormat.of().formatHex(hash);
        } catch (IOException e) {
            throw new DocumentLoadException("Cannot read " + id + EXTENSION, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean writtenByApp(String id) {
        try (BufferedReader reader = Files.newBufferedReader(existingFile(id), StandardCharsets.UTF_8)) {
            String first = reader.readLine();
            return first != null && CanonicalYamlWriter.HEADER.startsWith(first);
        } catch (IOException e) {
            return false;
        }
    }

    /** Backs up the current file (if any) to {@code .history/<id>/}, then writes atomically via a temp file. */
    @Override
    public void save(String id, Map<String, Object> tree) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new DocumentLoadException("Invalid document id '" + id + "'");
        }
        Path target = file(id);
        String yaml = new CanonicalYamlWriter().write(tree);
        try {
            Files.createDirectories(root);
            backup(id);
            Path temp = Files.createTempFile(root, "." + id + "-", ".tmp");
            Files.writeString(temp, yaml, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new DocumentLoadException("Cannot save " + target.getFileName() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String id) {
        Path file = existingFile(id);
        try {
            backup(id);
            Files.delete(file);
        } catch (IOException e) {
            throw new DocumentLoadException("Cannot delete " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** How a file is deleted; replaced in tests to simulate a failure. */
    interface FileDeleter {
        void delete(Path file) throws IOException;
    }

    static final int DELETE_SAFETY_KEEP = 20;
    private FileDeleter deleter = Files::delete;

    void useDeleter(FileDeleter deleter) {
        this.deleter = deleter;
    }

    @Override
    public synchronized String deleteAll(List<String> ids, String label) {
        java.util.SortedMap<String, byte[]> contents = new java.util.TreeMap<>();
        Map<String, byte[]> byId = new java.util.LinkedHashMap<>();
        try {
            for (String id : ids) {
                byte[] content = Files.readAllBytes(existingFile(id));
                contents.put(id + EXTENSION, content);
                byId.put(id, content);
            }
            Path history = root.resolve(HISTORY_DIR);
            String safeLabel = label.replaceAll("[^A-Za-z0-9._-]", "_");
            Path safety = history.resolve("delete-" + safeLabel + "-" + LocalDateTime.now().format(STAMP) + ".zip");
            ZipDataArchive.writeFile(safety, contents);
            ZipDataArchive.prune(history, "delete-", DELETE_SAFETY_KEEP);
            List<String> deleted = new java.util.ArrayList<>();
            try {
                for (String id : ids) {
                    backup(id);
                    deleter.delete(file(id));
                    deleted.add(id);
                }
            } catch (IOException | RuntimeException e) {
                for (String id : deleted) {
                    try {
                        Files.write(file(id), byId.get(id));
                    } catch (IOException restoreFailure) {
                        e.addSuppressed(restoreFailure);
                    }
                }
                throw new DocumentLoadException("Could not delete " + String.join(", ", ids) + " (" + e.getMessage()
                        + "); nothing was deleted. A copy is in " + relative(safety), e);
            }
            return relative(safety);
        } catch (IOException e) {
            throw new DocumentLoadException("Could not delete " + String.join(", ", ids) + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> listImages() {
        Path assets = root.resolve(ASSETS_DIR);
        if (!Files.isDirectory(assets)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(assets, 2)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().matches(".*\\.(jpe?g|png)"))
                    .map(this::relative)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new DocumentLoadException("Cannot list " + assets, e);
        }
    }

    /** Stores {@code fileName} (already sanitized by {@code ImageUpload}) under {@code assets/}; never overwrites. */
    @Override
    public String saveImage(String fileName, byte[] content) {
        if (!SAFE_ASSET_NAME.matcher(fileName).matches()) {
            throw new DocumentLoadException("Invalid photo file name '" + fileName + "'");
        }
        int dot = fileName.lastIndexOf('.');
        String stem = fileName.substring(0, dot);
        String extension = fileName.substring(dot);
        Path dir = root.resolve(ASSETS_DIR);
        try {
            Files.createDirectories(dir);
            for (int n = 1; ; n++) {
                Path target = dir.resolve(stem + (n == 1 ? "" : "-" + n) + extension).normalize();
                if (!target.startsWith(dir)) {
                    throw new DocumentLoadException("Photo path escapes the assets folder");
                }
                if (Files.exists(target)) {
                    if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), content)) {
                        return relative(target);
                    }
                    continue;
                }
                Path temp = Files.createTempFile(dir, ".upload-", ".tmp");
                try {
                    Files.write(temp, content);
                    Files.move(temp, target); // no REPLACE_EXISTING: fails instead of overwriting
                    return relative(target);
                } catch (FileAlreadyExistsException e) {
                    // created meanwhile by someone else: try the next name
                } finally {
                    Files.deleteIfExists(temp);
                }
            }
        } catch (IOException e) {
            throw new DocumentLoadException("Cannot save the photo in " + dir + ": " + e.getMessage(), e);
        }
    }

    private String relative(Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    /** Previous versions: {@code .history/<id>/<timestamp>.yaml}, newest {@value #HISTORY_LIMIT} kept. */
    private void backup(String id) throws IOException {
        Path current = file(id);
        if (!Files.isRegularFile(current)) {
            return;
        }
        Path dir = root.resolve(HISTORY_DIR).resolve(id);
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(STAMP);
        Path copy = dir.resolve(stamp + EXTENSION);
        for (int n = 2; Files.exists(copy); n++) {
            copy = dir.resolve(stamp + "-" + n + EXTENSION);
        }
        Files.copy(current, copy);
        try (Stream<Path> versions = Files.list(dir)) {
            List<Path> sorted = versions.sorted(Comparator.comparing(Path::toString).reversed()).toList();
            for (Path old : sorted.subList(Math.min(HISTORY_LIMIT, sorted.size()), sorted.size())) {
                Files.deleteIfExists(old);
            }
        }
    }

    private Path file(String id) {
        return root.resolve(id + EXTENSION);
    }

    private Path existingFile(String id) {
        if (!exists(id)) {
            throw new DocumentNotFoundException(String.valueOf(id));
        }
        return file(id);
    }

    @Override
    public Instant lastModified() {
        if (!Files.isDirectory(root)) {
            return Instant.EPOCH;
        }
        try (Stream<Path> files = Files.walk(root, 3)) {
            return files.map(FileDocumentRepository::modifiedTime)
                    .max(Instant::compareTo)
                    .orElse(Instant.EPOCH);
        } catch (IOException | java.io.UncheckedIOException e) {
            return Instant.now();
        }
    }

    @Override
    public byte[] load(String relativePath) {
        Path asset = root.resolve(relativePath).normalize();
        if (!asset.startsWith(root)) {
            throw new DocumentLoadException("Asset path '" + relativePath + "' is outside the data directory");
        }
        try {
            return Files.readAllBytes(asset);
        } catch (IOException e) {
            throw new DocumentLoadException("Cannot read asset '" + relativePath + "' (looked in " + asset + ")", e);
        }
    }

    private static Instant modifiedTime(Path path) {
        try {
            FileTime time = Files.getLastModifiedTime(path);
            return time.toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }
}
