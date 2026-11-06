package com.pdfgen.adapters.storage;

import com.pdfgen.application.DataArchive;
import com.pdfgen.application.DocumentLoadException;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Backup ZIPs of the data folder (ADR-0028). Layout: {@code manifest.json} first (format, version, creation time,
 * and path + size + SHA-256 of every file), then the files sorted by path: documents {@code <id>.yaml} at the top
 * and photos under {@code assets/}. {@code .history} is never included and never touched by a restore.
 *
 * <p>A restore is validated completely before anything changes (manifest, checksums, entry names, limits, YAML),
 * then the current documents and assets are swapped with the backup's; any failure during the swap moves the
 * originals back.
 */
public final class ZipDataArchive implements DataArchive {

    static final String MANIFEST = "manifest.json";
    static final String FORMAT = "pdf-generator-backup";
    static final int VERSION = 1;
    static final int MAX_ENTRIES = 1000;
    static final long MAX_TOTAL_BYTES = 200L * 1024 * 1024;
    static final int SAFETY_RESTORE_KEEP = 5;

    private static final Pattern DOCUMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*\\.yaml");
    private static final Pattern RESERVED = Pattern.compile("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?");
    private static final Pattern FORBIDDEN_CHARS = Pattern.compile("[<>:\"|?*\\\\\\x00-\\x1f\\x7f]");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final Path root;

    public ZipDataArchive(Path dataDir) {
        this.root = dataDir.toAbsolutePath().normalize();
    }

    // ---- export -------------------------------------------------------------------------------

    @Override
    public synchronized void export(OutputStream out) throws IOException {
        write(out, currentFiles());
    }

    /** Documents at the top of the data folder and everything under {@code assets/} (temp files excluded). */
    SortedMap<String, byte[]> currentFiles() throws IOException {
        SortedMap<String, byte[]> files = new TreeMap<>();
        for (Path path : currentPaths()) {
            files.put(relative(path), Files.readAllBytes(path));
        }
        return files;
    }

    private List<Path> currentPaths() throws IOException {
        List<Path> paths = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return paths;
        }
        try (Stream<Path> top = Files.list(root)) {
            top.filter(Files::isRegularFile).filter(p -> DOCUMENT.matcher(p.getFileName().toString()).matches())
                    .forEach(paths::add);
        }
        Path assets = root.resolve(FileDocumentRepository.ASSETS_DIR);
        if (Files.isDirectory(assets)) {
            try (Stream<Path> walk = Files.walk(assets)) {
                walk.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().startsWith("."))
                        .forEach(paths::add);
            }
        }
        paths.sort(Comparator.comparing(this::relative));
        return paths;
    }

    /** Writes {@code files} (path → content) in the backup format. Also used for the safety copies. */
    static void write(OutputStream out, SortedMap<String, byte[]> files) throws IOException {
        Instant now = Instant.now();
        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        putEntry(zip, MANIFEST, manifest(files, now).getBytes(StandardCharsets.UTF_8), now);
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            putEntry(zip, file.getKey(), file.getValue(), now);
        }
        zip.finish();
        zip.flush();
    }

    /** Writes a backup-format ZIP of {@code files} to {@code target} (via a temp file). */
    static void writeFile(Path target, SortedMap<String, byte[]> files) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".zip-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                write(out, files);
            }
            Files.move(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void putEntry(ZipOutputStream zip, String name, byte[] content, Instant time) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setLastModifiedTime(FileTime.from(time));
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    private static String manifest(SortedMap<String, byte[]> files, Instant created) {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"format\": \"").append(FORMAT).append("\",\n  \"version\": ").append(VERSION)
                .append(",\n  \"created\": \"").append(created).append("\",\n  \"files\": [");
        String separator = "\n";
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            json.append(separator).append("    {\"path\": \"").append(jsonEscape(file.getKey()))
                    .append("\", \"size\": ").append(file.getValue().length)
                    .append(", \"sha256\": \"").append(sha256(file.getValue())).append("\"}");
            separator = ",\n";
        }
        return json.append("\n  ]\n}\n").toString();
    }

    private static String jsonEscape(String text) {
        StringBuilder escaped = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- restore ------------------------------------------------------------------------------

    @Override
    public synchronized Restored restore(Path backupZip) {
        SortedMap<String, byte[]> incoming = readAndValidate(backupZip);
        try {
            List<String> before = documentIds(currentPaths().stream().map(this::relative).toList());
            List<String> after = documentIds(incoming.keySet());
            String stamp = LocalDateTime.now().format(STAMP);
            Path history = root.resolve(FileDocumentRepository.HISTORY_DIR);
            Path safety = history.resolve("restore-" + stamp + ".zip");
            writeFile(safety, currentFiles());
            prune(history, "restore-", SAFETY_RESTORE_KEEP);
            swap(incoming, history.resolve("restore-staging-" + stamp), history.resolve("restore-old-" + stamp));
            List<String> removed = before.stream().filter(id -> !after.contains(id)).toList();
            return new Restored(after, removed, relative(safety));
        } catch (IOException e) {
            throw new DocumentLoadException("The backup could not be restored; your data was left as it was ("
                    + e.getMessage() + ")", e);
        }
    }

    /** Reads the whole backup into memory, checking everything; throws before any change. */
    SortedMap<String, byte[]> readAndValidate(Path backupZip) {
        try (ZipFile zip = new ZipFile(backupZip.toFile(), StandardCharsets.UTF_8)) {
            List<? extends ZipEntry> entries = Collections.list(zip.entries());
            if (entries.size() > MAX_ENTRIES + 1) {
                throw invalid("more than " + MAX_ENTRIES + " files");
            }
            if (entries.isEmpty() || !entries.getFirst().getName().equals(MANIFEST)) {
                throw invalid("the first file must be " + MANIFEST);
            }
            Set<String> seen = new HashSet<>();
            SortedMap<String, byte[]> files = new TreeMap<>();
            long[] budget = {MAX_TOTAL_BYTES};
            byte[] manifest = null;
            for (ZipEntry entry : entries) {
                String name = entry.getName();
                if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                    throw invalid("'" + name + "' appears twice");
                }
                if (entry.isDirectory()) {
                    requireSafeName(name.substring(0, name.length() - 1));
                    continue;
                }
                byte[] content = readBounded(zip, entry, budget);
                if (name.equals(MANIFEST)) {
                    manifest = content;
                } else {
                    requireSafeName(name);
                    files.put(name, content);
                }
            }
            checkManifest(manifest, files);
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                if (file.getKey().endsWith(".yaml")) {
                    requireYamlMap(file.getKey(), file.getValue());
                }
            }
            return files;
        } catch (ZipException e) {
            throw invalid("the file is not a ZIP archive or is damaged (" + e.getMessage() + ")");
        } catch (IOException e) {
            throw invalid("cannot read it (" + e.getMessage() + ")");
        }
    }

    private static byte[] readBounded(ZipFile zip, ZipEntry entry, long[] budget) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) {
                budget[0] -= n;
                if (budget[0] < 0) {
                    throw invalid("it unpacks to more than " + MAX_TOTAL_BYTES / (1024 * 1024) + " MB");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    /**
     * Only {@code <id>.yaml} at the top and files under {@code assets/}; no {@code ..}, absolute paths, drive
     * letters, backslashes, control characters or names Windows cannot create.
     */
    static void requireSafeName(String name) {
        if (name.isEmpty() || name.startsWith("/") || FORBIDDEN_CHARS.matcher(name).find()) {
            throw invalid("unsafe file name '" + printable(name) + "'");
        }
        String[] segments = name.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.endsWith(".")
                    || segment.endsWith(" ") || RESERVED.matcher(segment).matches()) {
                throw invalid("unsafe file name '" + printable(name) + "'");
            }
        }
        boolean document = segments.length == 1 && DOCUMENT.matcher(name).matches();
        boolean asset = segments.length >= 2 && segments[0].equals(FileDocumentRepository.ASSETS_DIR);
        boolean assetsFolder = segments.length == 1 && name.equals(FileDocumentRepository.ASSETS_DIR);
        if (!document && !asset && !assetsFolder) {
            throw invalid("unexpected file '" + printable(name) + "' (only <name>.yaml and assets/ are restored)");
        }
    }

    private static String printable(String name) {
        return name.replaceAll("[\\x00-\\x1f\\x7f]", "?");
    }

    private static void checkManifest(byte[] manifest, SortedMap<String, byte[]> files) {
        Object parsed;
        try {
            parsed = StrictYaml.create().load(new StringReader(new String(manifest, StandardCharsets.UTF_8)));
        } catch (YAMLException e) {
            throw invalid(MANIFEST + " cannot be read");
        }
        if (!(parsed instanceof Map<?, ?> map) || !FORMAT.equals(map.get("format"))) {
            throw invalid(MANIFEST + " is not from PDF Generator");
        }
        if (!String.valueOf(VERSION).equals(String.valueOf(map.get("version")))) {
            throw invalid("backup format version " + map.get("version") + " is not supported");
        }
        if (!(map.get("files") instanceof List<?> listed)) {
            throw invalid(MANIFEST + " has no file list");
        }
        Map<String, String> expected = new LinkedHashMap<>();
        for (Object item : listed) {
            if (!(item instanceof Map<?, ?> file) || !(file.get("path") instanceof String path)
                    || !(file.get("sha256") instanceof String hash)) {
                throw invalid(MANIFEST + " has a malformed file entry");
            }
            expected.put(path, hash);
        }
        if (!expected.keySet().equals(files.keySet())) {
            Set<String> missing = new java.util.TreeSet<>(expected.keySet());
            missing.removeAll(files.keySet());
            Set<String> extra = new java.util.TreeSet<>(files.keySet());
            extra.removeAll(expected.keySet());
            throw invalid("the files do not match " + MANIFEST + " (missing: " + missing + ", not listed: " + extra + ")");
        }
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            if (!sha256(file.getValue()).equalsIgnoreCase(expected.get(file.getKey()))) {
                throw invalid("'" + file.getKey() + "' is damaged (checksum mismatch)");
            }
        }
    }

    private static void requireYamlMap(String name, byte[] content) {
        Object parsed;
        try {
            parsed = StrictYaml.create().load(new StringReader(new String(content, StandardCharsets.UTF_8)));
        } catch (YAMLException e) {
            throw invalid("'" + name + "' cannot be parsed: " + e.getMessage());
        }
        if (!(parsed instanceof Map<?, ?>)) {
            throw invalid("'" + name + "' must contain a YAML map at the top level");
        }
    }

    /** Moves the current documents and assets aside, puts the backup's in place; rolls back on failure. */
    private void swap(SortedMap<String, byte[]> incoming, Path staging, Path old) throws IOException {
        for (Map.Entry<String, byte[]> file : incoming.entrySet()) {
            Path target = staging.resolve(file.getKey()).normalize();
            if (!target.startsWith(staging)) {
                throw invalid("unsafe file name '" + file.getKey() + "'");
            }
            Files.createDirectories(target.getParent());
            Files.write(target, file.getValue());
        }
        Files.createDirectories(staging);
        Files.createDirectories(old);
        Deque<Path[]> moves = new ArrayDeque<>(); // {from, to}, for the rollback
        try {
            for (Path current : currentDocumentsAndAssetsFolder()) {
                move(current, old.resolve(current.getFileName().toString()), moves);
            }
            try (Stream<Path> staged = Files.list(staging)) {
                for (Path path : staged.sorted().toList()) {
                    move(path, root.resolve(path.getFileName().toString()), moves);
                }
            }
        } catch (IOException | RuntimeException e) {
            rollback(moves, e);
            throw e;
        }
        deleteTree(old);
        deleteTree(staging);
    }

    private List<Path> currentDocumentsAndAssetsFolder() throws IOException {
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> top = Files.list(root)) {
            top.filter(p -> Files.isRegularFile(p) && DOCUMENT.matcher(p.getFileName().toString()).matches())
                    .sorted().forEach(paths::add);
        }
        Path assets = root.resolve(FileDocumentRepository.ASSETS_DIR);
        if (Files.isDirectory(assets)) {
            paths.add(assets);
        }
        return paths;
    }

    private static void move(Path from, Path to, Deque<Path[]> moves) throws IOException {
        Files.move(from, to);
        moves.push(new Path[] {from, to});
    }

    private static void rollback(Deque<Path[]> moves, Exception cause) {
        while (!moves.isEmpty()) {
            Path[] move = moves.pop();
            try {
                Files.move(move[1], move[0], StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                cause.addSuppressed(e);
            }
        }
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    /** Keeps the newest {@code keep} files {@code <prefix><timestamp>.zip} in {@code dir}. */
    static void prune(Path dir, String prefix, int keep) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> zips = files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(prefix)
                            && p.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparing(Path::toString).reversed()).toList();
            for (Path stale : zips.subList(Math.min(keep, zips.size()), zips.size())) {
                Files.deleteIfExists(stale);
            }
        }
    }

    private static List<String> documentIds(java.util.Collection<String> paths) {
        return paths.stream().filter(p -> !p.contains("/") && p.endsWith(".yaml"))
                .map(p -> p.substring(0, p.length() - ".yaml".length())).sorted().toList();
    }

    private String relative(Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static InvalidBackupException invalid(String message) {
        return new InvalidBackupException(message);
    }

    /** For tests: the entry names of a backup, in order. */
    static List<String> entryNames(Path zipFile) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            List<String> names = new ArrayList<>();
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                names.add(e.nextElement().getName());
            }
            return names;
        }
    }
}
