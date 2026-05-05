package com.xa.mass.trace.sink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Asynchronous JSONL (newline-delimited JSON) execution event sink.
 *
 * <p>Events are handed off to a bounded in-memory queue and written by a dedicated
 * background thread, so hot-path callers are never blocked by I/O. If the queue is
 * full the event is silently dropped and a drop counter is incremented.
 *
 * <p>Output files rotate when {@code rotateAfterLines} lines have been written.
 * File names follow the pattern {@code events-<timestamp>.jsonl}.
 */
public final class JsonlExecutionEventSink implements ExecutionEventSink, Closeable {

    private static final Logger LOG = Logger.getLogger(JsonlExecutionEventSink.class.getName());
    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final ExecutionEvent POISON_PILL = ExecutionEvent.builder()
            .eventType(ExecutionEventType.WORKER_OFFLINE)
            .build();

    private final ObjectMapper mapper;
    private final Path outputDir;
    private final int rotateAfterLines;
    private final BlockingQueue<ExecutionEvent> queue;
    private final Thread writer;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong fileSeq = new AtomicLong();

    private volatile boolean closed = false;

    public JsonlExecutionEventSink(String outputDir, int queueCapacity, int rotateAfterLines) {
        this.outputDir = Paths.get(outputDir);
        this.rotateAfterLines = rotateAfterLines;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.mapper = buildMapper();

        try {
            Files.createDirectories(this.outputDir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create trace output directory: " + outputDir, e);
        }

        this.writer = new Thread(this::writeLoop, "mass-trace-sink-writer");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    // ── ExecutionEventSink ────────────────────────────────────────────────────

    @Override
    public void emit(ExecutionEvent event) {
        if (closed) {
            return;
        }
        if (!queue.offer(event)) {
            dropped.incrementAndGet();
        }
    }

    // ── background writer loop ────────────────────────────────────────────────

    private void writeLoop() {
        BufferedWriter current = null;
        int lineCount = 0;

        try {
            while (true) {
                ExecutionEvent event = queue.poll(200, TimeUnit.MILLISECONDS);

                if (event == null) {
                    continue;
                }
                if (event == POISON_PILL) {
                    break;
                }

                // open file on first write (avoids empty files)
                if (current == null) {
                    current = openNextFile();
                }

                String line = mapper.writeValueAsString(event);
                current.write(line);
                current.newLine();
                lineCount++;

                if (lineCount >= rotateAfterLines) {
                    current.close();
                    current = null;
                    lineCount = 0;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "mass-trace-sink writer error", e);
        } finally {
            flush(current);
        }
    }

    private BufferedWriter openNextFile() throws IOException {
        String name = "events-" + FILE_TS.format(Instant.now()) + "-" + fileSeq.incrementAndGet() + ".jsonl";
        Path file = outputDir.resolve(name);
        return Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void flush(BufferedWriter w) {
        if (w != null) {
            try {
                w.close();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to close trace file", e);
            }
        }
    }

    // ── Closeable ─────────────────────────────────────────────────────────────

    @Override
    public void close() {
        closed = true;
        queue.offer(POISON_PILL);
        try {
            writer.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    public long getDroppedCount() {
        return dropped.get();
    }

    private static ObjectMapper buildMapper() {
        ObjectMapper m = new ObjectMapper();
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return m;
    }
}
