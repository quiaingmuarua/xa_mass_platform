package com.xa.mass.trace.sink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xa.mass.trace.api.ExecutionEvent;
import com.xa.mass.trace.api.ExecutionEventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Async JSONL rotating file sink for execution trace events.
 *
 * <p>Events submitted via {@link #emit} are placed onto a bounded internal queue
 * and written by a single background daemon thread. If the queue is full the
 * event is dropped and a warning is logged (rate-limited). The calling thread
 * is never blocked.</p>
 *
 * <p>When the active file exceeds {@link JsonlSinkConfig#rotateSizeBytes()} it
 * is renamed to {@code events-{timestamp}.jsonl} and a new
 * {@code events-current.jsonl} is opened.</p>
 *
 * <p>Call {@link #close()} (or use as a try-with-resources) to drain remaining
 * queued events and close the writer before shutdown.</p>
 */
public final class JsonlExecutionEventSink implements ExecutionEventSink, Closeable {

    private static final Logger log = LoggerFactory.getLogger(JsonlExecutionEventSink.class);

    private static final String CURRENT_FILE_NAME = "events-current.jsonl";
    private static final DateTimeFormatter ROTATE_TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

    /** Sentinel used to signal the writer thread to drain and exit. */
    private static final ExecutionEvent POISON = ExecutionEvent.builder(
            com.xa.mass.trace.api.ExecutionEventType.TASK_STATUS_CHANGED).build();

    private static final long DROP_LOG_INTERVAL_MS = 10_000L;

    private final JsonlSinkConfig config;
    private final ObjectMapper mapper;
    private final LinkedBlockingQueue<ExecutionEvent> queue;
    private final Thread writerThread;

    private final AtomicLong droppedCount = new AtomicLong(0);
    private volatile long lastDropLogMs = 0;

    private BufferedWriter writer;
    private long currentFileSize = 0;
    private volatile boolean closed = false;

    public JsonlExecutionEventSink(JsonlSinkConfig config) {
        this.config = config;
        this.mapper = new ObjectMapper();
        this.mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        this.mapper.findAndRegisterModules();
        this.queue = new LinkedBlockingQueue<>(config.queueCapacity());

        try {
            Files.createDirectories(config.directory());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create trace directory: " + config.directory(), e);
        }

        this.writerThread = new Thread(this::drainLoop, "trace-sink-writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    @Override
    public void emit(ExecutionEvent event) {
        if (event == null || closed) {
            return;
        }
        boolean accepted = queue.offer(event);
        if (!accepted) {
            long dropped = droppedCount.incrementAndGet();
            long now = System.currentTimeMillis();
            if (now - lastDropLogMs > DROP_LOG_INTERVAL_MS) {
                lastDropLogMs = now;
                log.warn("trace-sink: event queue full, dropped {} events (total since start)", dropped);
            }
        }
    }

    /**
     * Signals the writer thread to stop after draining remaining events, then
     * waits for it to finish and closes the underlying file.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        queue.offer(POISON);
        try {
            writerThread.join(10_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Internal writer loop
    // -------------------------------------------------------------------------

    private void drainLoop() {
        try {
            try {
                openWriterIfNeeded();
            } catch (IOException e) {
                log.error("trace-sink: failed to open initial writer, sink will not write events", e);
                return;
            }
            while (true) {
                ExecutionEvent event = queue.poll(500, TimeUnit.MILLISECONDS);
                if (event == null) {
                    continue;
                }
                if (event == POISON) {
                    // drain everything remaining before exit
                    ExecutionEvent remaining;
                    while ((remaining = queue.poll()) != null && remaining != POISON) {
                        writeEvent(remaining);
                    }
                    break;
                }
                writeEvent(event);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeWriter();
        }
    }

    private void writeEvent(ExecutionEvent event) {
        try {
            openWriterIfNeeded();
            rotateIfNeeded();
            String line = mapper.writeValueAsString(event);
            writer.write(line);
            writer.newLine();
            writer.flush();
            currentFileSize += line.getBytes(StandardCharsets.UTF_8).length
                    + System.lineSeparator().getBytes(StandardCharsets.UTF_8).length;
        } catch (IOException e) {
            log.error("trace-sink: failed to write event", e);
        }
    }

    private void openWriterIfNeeded() throws IOException {
        if (writer != null) {
            return;
        }
        Path file = config.directory().resolve(CURRENT_FILE_NAME);
        writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try {
            currentFileSize = Files.size(file);
        } catch (java.nio.file.NoSuchFileException e) {
            currentFileSize = 0;
        }
    }

    private void rotateIfNeeded() throws IOException {
        if (currentFileSize < config.rotateSizeBytes()) {
            return;
        }
        closeWriter();
        Path current = config.directory().resolve(CURRENT_FILE_NAME);
        if (Files.exists(current)) {
            String timestamp = ROTATE_TS_FORMAT.format(Instant.now());
            Path rotated = config.directory().resolve("events-" + timestamp + ".jsonl");
            Files.move(current, rotated);
        }
        writer = null;
        currentFileSize = 0;
        openWriterIfNeeded();
    }

    private void closeWriter() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                log.error("trace-sink: failed to close writer", e);
            } finally {
                writer = null;
            }
        }
    }

    /**
     * Returns the total number of events dropped due to queue saturation since
     * this sink was created.
     */
    public long droppedCount() {
        return droppedCount.get();
    }
}
