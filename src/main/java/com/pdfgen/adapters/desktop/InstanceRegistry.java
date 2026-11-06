package com.pdfgen.adapters.desktop;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/**
 * Single-instance guard (ADR-0021).
 * <ul>
 *   <li>{@code run/instance.lock} is held with an OS file lock for the whole life of the process.
 *       The OS releases it even after a crash, so it can never go stale.</li>
 *   <li>{@code run/instance.properties} (port, pid, id) is written once the server is up, and deleted on shutdown.
 *       A second launch that cannot get the lock reads it and simply opens the browser at that port.</li>
 * </ul>
 */
public final class InstanceRegistry implements AutoCloseable {

    /** What a running instance publishes about itself. */
    public record Instance(String id, int port, long pid) {
        public String url() {
            return "http://127.0.0.1:" + port + "/";
        }
    }

    private final Path lockFile;
    private final Path infoFile;
    private final String instanceId = UUID.randomUUID().toString();
    private FileChannel channel;
    private FileLock lock;

    public InstanceRegistry(Path runDir) {
        this.lockFile = runDir.resolve("instance.lock");
        this.infoFile = runDir.resolve("instance.properties");
    }

    public String instanceId() {
        return instanceId;
    }

    /** @return true if this process now owns the lock; false if another process holds it. */
    public synchronized boolean tryLock() {
        try {
            Files.createDirectories(lockFile.getParent());
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create " + lockFile, e);
        }
        if (lock == null) {
            closeChannel();
            return false;
        }
        return true;
    }

    /** Publishes this instance's port. Written to a temp file and moved, so readers never see half a file. */
    public void publish(int port) {
        Properties properties = new Properties();
        properties.setProperty("id", instanceId);
        properties.setProperty("port", String.valueOf(port));
        properties.setProperty("pid", String.valueOf(ProcessHandle.current().pid()));
        properties.setProperty("startedAt", Instant.now().toString());
        Path temp = infoFile.resolveSibling(infoFile.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            properties.store(writer, "pdf-generator running instance - deleted on shutdown");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + temp, e);
        }
        try {
            Files.move(temp, infoFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            moveNonAtomic(temp);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + infoFile, e);
        }
    }

    /** The instance published by whoever holds the lock, if its file exists and is readable. */
    public Optional<Instance> readPublished() {
        if (!Files.isRegularFile(infoFile)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(infoFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return Optional.of(new Instance(properties.getProperty("id", ""),
                    Integer.parseInt(properties.getProperty("port", "0")),
                    Long.parseLong(properties.getProperty("pid", "0"))));
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Deletes the published info and releases the lock. Safe to call more than once. */
    @Override
    public synchronized void close() {
        if (lock == null) {
            return;
        }
        try {
            Files.deleteIfExists(infoFile);
            lock.release();
        } catch (IOException e) {
            // Best effort: the OS releases the lock when the process exits anyway.
        } finally {
            lock = null;
            closeChannel();
        }
    }

    private void moveNonAtomic(Path temp) {
        try {
            Files.move(temp, infoFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + infoFile, e);
        }
    }

    private void closeChannel() {
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException ignored) {
            // nothing useful to do
        } finally {
            channel = null;
        }
    }
}
