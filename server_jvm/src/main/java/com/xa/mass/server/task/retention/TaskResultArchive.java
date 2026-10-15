package com.xa.mass.server.task.retention;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.*;
import com.xa.mass.server.task.result.TaskResultRecord;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Sequential, immutable archives. No message-ID index or execution restore path. */
@Service
public final class TaskResultArchive {
    private final TaskRuntime runtime;
    private final ObjectMapper mapper;
    private final Path root;

    public TaskResultArchive(TaskRuntime runtime, ObjectMapper mapper, RedisKeyspace space, TaskRetentionProperties properties) {
        this.runtime = runtime; this.mapper = mapper;
        this.root = properties.directory().resolve(digest(space.base().getBytes(StandardCharsets.UTF_8)));
    }

    public record Manifest(int version, TaskDescriptor task, long frozenAtMillis, long records, long bytes, String sha256) { }

    public void publish(TaskDescriptor task, TaskDataState state) throws IOException {
        if (state.phase() != TaskDataPhase.FROZEN || state.frozenAtMillis() == null)
            throw new IllegalStateException("Archive requires frozen Task data");
        Path target = directory(task.taskId());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            try {
                var saved = verify(task.taskId());
                if (saved.task().equals(task) && saved.frozenAtMillis() == state.frozenAtMillis()) return;
            } catch (IOException | RuntimeException invalid) { /* Redis still owns the complete frozen source. */ }
            deleteTree(target);
        }
        Path pending = pending(task.taskId());
        deleteTree(pending);
        Files.createDirectories(root);
        requireDirectory(root);
        Files.createDirectory(pending);
        Path data = pending.resolve("results.jsonl");
        long records = 0;
        var seen = new HashSet<String>();
        var cursors = new HashSet<String>();
        String cursor = "0";
        try (var writer = Files.newBufferedWriter(data, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            do {
                if (!cursors.add(cursor)) throw new IOException("Result scan cursor repeated");
                var page = runtime.scanTaskItemResults(task.taskId(), cursor, TaskRuntime.MAX_RESULT_SCAN_COUNT_HINT);
                var ids = new ArrayList<>(page.results().keySet());
                for (int from = 0; from < ids.size(); from += 100) {
                    var batch = ids.subList(from, Math.min(from + 100, ids.size()));
                    var items = runtime.loadTaskItems(task.taskId(), batch);
                    for (String id : batch) if (seen.add(id)) {
                        writer.write(mapper.writeValueAsString(new TaskResultRecord(id, page.results().get(id), items.get(id))));
                        writer.newLine(); records++;
                    }
                }
                cursor = page.nextCursor();
            } while (!cursor.equals("0"));
        }
        force(data);
        var written = fingerprint(data);
        if (written.records() != records) throw new IOException("Archive record count differs");
        var manifest = new Manifest(1, task, state.frozenAtMillis(), records, written.bytes(), written.sha256());
        Path metadata = pending.resolve("manifest.json");
        Files.writeString(metadata, mapper.writeValueAsString(manifest), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        force(metadata);
        // Publication must be atomic on the configured filesystem. Failure keeps
        // the Redis source and the pending directory for an explicit retry.
        Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE);
        verify(task.taskId());
    }

    public List<TaskResultRecord> preview(String taskId, int limit) throws IOException {
        verify(taskId);
        var rows = new ArrayList<TaskResultRecord>();
        try (var reader = Files.newBufferedReader(directory(taskId).resolve("results.jsonl"), StandardCharsets.UTF_8)) {
            String line;
            while (rows.size() < limit && (line = reader.readLine()) != null) rows.add(readRow(line));
        }
        return List.copyOf(rows);
    }

    public void forEach(String taskId, Consumer<TaskResultRecord> consumer) throws IOException {
        var manifest = verify(taskId);
        long count = 0;
        try (var reader = Files.newBufferedReader(directory(taskId).resolve("results.jsonl"), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) { consumer.accept(readRow(line)); count++; }
        }
        if (count != manifest.records()) throw new IOException("Archive record count differs");
    }

    public Manifest verify(String taskId) throws IOException {
        Path folder = directory(taskId);
        requireDirectory(root); requireDirectory(folder);
        Path data = folder.resolve("results.jsonl");
        Path metadata = folder.resolve("manifest.json");
        if (!Files.isRegularFile(data, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Task archive is missing");
        var manifest = mapper.readValue(Files.readString(metadata, StandardCharsets.UTF_8), Manifest.class);
        var observed = fingerprint(data);
        if (manifest.version() != 1 || manifest.task() == null || !taskId.equals(manifest.task().taskId())
                || manifest.frozenAtMillis() <= 0 || manifest.records() != observed.records() || observed.bytes() != manifest.bytes()
                || !observed.sha256().equals(manifest.sha256())) throw new IOException("Task archive is corrupt");
        return manifest;
    }

    public void remove(String taskId) throws IOException { deleteTree(pending(taskId)); deleteTree(directory(taskId)); }

    private TaskResultRecord readRow(String line) throws IOException {
        var row = mapper.readValue(line, TaskResultRecord.class);
        if (row.messageId() == null || row.messageId().isBlank() || row.result() == null
                || row.item() != null && !row.messageId().equals(row.item().messageId()))
            throw new IOException("Archive result association is corrupt");
        return row;
    }
    private Path directory(String taskId) { return root.resolve(digest(taskId.getBytes(StandardCharsets.UTF_8))); }
    private Path pending(String taskId) { return root.resolve(directory(taskId).getFileName() + ".pending"); }
    private void deleteTree(Path folder) throws IOException {
        if (!folder.normalize().startsWith(root) || folder.equals(root)) throw new IOException("Invalid archive path");
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) return;
        requireDirectory(root); requireDirectory(folder);
        try (var paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    private static void requireDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Archive directory is missing or invalid");
    }
    private static void force(Path file) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) { channel.force(true); }
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String digest(byte[] value) { return HexFormat.of().formatHex(sha256().digest(value)); }
    private record Fingerprint(long bytes, long records, String sha256) { }
    private static Fingerprint fingerprint(Path file) throws IOException {
        var digest = sha256();
        long bytes = 0, records = 0;
        int last = '\n';
        byte[] buffer = new byte[65536];
        try (var input = Files.newInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
                bytes += count;
                for (int i = 0; i < count; i++) if (buffer[i] == '\n') records++;
                if (count > 0) last = buffer[count - 1];
            }
        }
        if (last != '\n') throw new IOException("Archive last record is incomplete");
        return new Fingerprint(bytes, records, HexFormat.of().formatHex(digest.digest()));
    }
}
