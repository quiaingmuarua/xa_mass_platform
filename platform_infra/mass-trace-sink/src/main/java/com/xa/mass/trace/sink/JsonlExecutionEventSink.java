package com.xa.mass.trace.sink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

/**
 * Asynchronous JSONL (newline-delimited JSON) execution event sink.
 *
 * <p>Events are handed off to a bounded in-memory queue and written by a dedicated
 * background thread, so hot-path callers are never blocked by I/O.
 *
 * <p>When the queue is full, behaviour is controlled by {@link OverflowPolicy}:
 * <ul>
 *   <li>{@link OverflowPolicy#DROP} (default): the event is discarded, the drop counter
 *       is incremented, and a rate-limited WARN is logged once per 1000 drops.</li>
 *   <li>{@link OverflowPolicy#FALLBACK_SYNC}: the caller thread writes directly to the
 *       current output file. <b>Debug / low-throughput use only — do not use on hot
 *       paths.</b></li>
 * </ul>
 *
 * <p>Output files rotate when {@code rotateAfterLines} lines have been written.
 * File names follow the pattern {@code events-<yyyyMMddTHHmmssZ>-<seq>.jsonl}.
 */
public final class JsonlExecutionEventSink implements ExecutionEventSink, Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(JsonlExecutionEventSink.class);
    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final ExecutionEvent POISON_PILL = ExecutionEvent.builder()
            .eventType(ExecutionEventType.WORKER_OFFLINE)
            .build();

    private final ObjectMapper mapper;
    private final Path outputDir;
    private final int rotateAfterLines;
    private final OverflowPolicy overflowPolicy;
    private final long shutdownDrainTimeoutMs;
    private final BlockingQueue<ExecutionEvent> queue;
    private final Thread writer;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong fileSeq = new AtomicLong();

    /** Guards {@link #currentFile} and {@link #lineCount} for FALLBACK_SYNC cross-thread access. */
    private final Object fileLock = new Object();
    private BufferedWriter currentFile = null;  // @GuardedBy("fileLock")
    private int lineCount = 0;                  // @GuardedBy("fileLock")

    private volatile boolean closed = false;

    /**
     * Convenience constructor with {@link OverflowPolicy#DROP} and a 5-second shutdown drain.
     */
    public JsonlExecutionEventSink(String outputDir, int queueCapacity, int rotateAfterLines) {
        this(outputDir, queueCapacity, rotateAfterLines, OverflowPolicy.DROP, 5_000);
    }

    public JsonlExecutionEventSink(
            String outputDir,
            int queueCapacity,
            int rotateAfterLines,
            OverflowPolicy overflowPolicy,
            long shutdownDrainTimeoutMs) {
        this.outputDir = Paths.get(outputDir);
        this.rotateAfterLines = rotateAfterLines;
        this.overflowPolicy = overflowPolicy;
        this.shutdownDrainTimeoutMs = shutdownDrainTimeoutMs;
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
        if (queue.offer(event)) {
            return;
        }

        // Queue is full — apply overflow policy.
        if (overflowPolicy == OverflowPolicy.FALLBACK_SYNC) {
            writeFallbackSync(event);
        } else {
            // DROP policy
            long count = dropped.incrementAndGet();
            if (count % 1000 == 0) {
                LOG.warn("mass-trace-sink: {} events dropped (queue full)", count);
            }
        }
    }

    // ── FALLBACK_SYNC path ────────────────────────────────────────────────────

    private void writeFallbackSync(ExecutionEvent event) {
        synchronized (fileLock) {
            try {
                if (currentFile == null) {
                    currentFile = openNextFile();
                }
                writeEvent(currentFile, event);
                currentFile.flush();
                lineCount++;
                if (lineCount >= rotateAfterLines) {
                    currentFile.close();
                    currentFile = null;
                    lineCount = 0;
                }
            } catch (IOException e) {
                long count = dropped.incrementAndGet();
                if (count % 1000 == 0) {
                    LOG.warn("mass-trace-sink: {} events dropped (fallback-sync I/O error)", count, e);
                }
            }
        }
    }

    // ── background writer loop ────────────────────────────────────────────────

    private void writeLoop() {
        try {
            while (true) {
                ExecutionEvent event = queue.poll(200, TimeUnit.MILLISECONDS);

                if (event == null) {
                    continue;
                }
                if (event == POISON_PILL) {
                    break;
                }

                synchronized (fileLock) {
                    // open file on first write (avoids empty files)
                    if (currentFile == null) {
                        currentFile = openNextFile();
                    }

                    writeEvent(currentFile, event);
                    lineCount++;

                    if (lineCount >= rotateAfterLines) {
                        currentFile.close();
                        currentFile = null;
                        lineCount = 0;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.error("mass-trace-sink writer error", e);
        } finally {
            synchronized (fileLock) {
                flush(currentFile);
                currentFile = null;
            }
        }
    }

    // ── shared write helper ───────────────────────────────────────────────────

    private void writeEvent(BufferedWriter w, ExecutionEvent event) throws IOException {
        w.write(mapper.writeValueAsString(event));
        w.newLine();
    }

    // ── file management ───────────────────────────────────────────────────────

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
                LOG.warn("Failed to close trace file", e);
            }
        }
    }

    // ── Closeable ─────────────────────────────────────────────────────────────

    @Override
    public void close() {
        closed = true;
        try {
            // Use blocking put so POISON_PILL is guaranteed to reach the writer
            // even if the queue is at capacity.  Since closed=true prevents new
            // emits, the background writer will drain the current occupant first.
            queue.put(POISON_PILL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            writer.join(shutdownDrainTimeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (writer.isAlive()) {
            LOG.warn("mass-trace-sink: writer did not finish within {}ms; ~{} events may remain in queue",
                    shutdownDrainTimeoutMs, queue.size());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    public long getDroppedCount() {
        return dropped.get();
    }

    private static ObjectMapper buildMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return m;
    }
}
