package com.xa.mass.testing.chaos.support;

import com.xa.mass.testing.support.TestingPaths;
import com.xa.mass.trace.sink.ExecutionEvent;
import com.xa.mass.trace.sink.ExecutionEventSink;
import com.xa.mass.trace.sink.JsonlExecutionEventSink;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared chaos trace proof surface: capture events in-memory for fast assertions and
 * persist canonical JSONL so named trace analyzers can run on the same scenario.
 */
public final class ChaosTraceArtifacts implements ExecutionEventSink, AutoCloseable {

    private static final int DEFAULT_QUEUE_CAPACITY = 4_096;
    private static final int DEFAULT_ROTATE_AFTER_LINES = 10_000;

    private final CapturingExecutionEventSink captureSink;
    private final JsonlExecutionEventSink jsonlSink;
    private final Path outputDir;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private ChaosTraceArtifacts(CapturingExecutionEventSink captureSink,
                                JsonlExecutionEventSink jsonlSink,
                                Path outputDir) {
        this.captureSink = captureSink;
        this.jsonlSink = jsonlSink;
        this.outputDir = outputDir;
    }

    public static ChaosTraceArtifacts create(String scenarioId) throws Exception {
        Path traceDir = TestingPaths.reportDir("chaos-traces")
                .resolve(scenarioId + "-" + ChaosSupport.timestampSuffix());
        Files.createDirectories(traceDir);
        return new ChaosTraceArtifacts(
                new CapturingExecutionEventSink(),
                new JsonlExecutionEventSink(traceDir.toString(), DEFAULT_QUEUE_CAPACITY, DEFAULT_ROTATE_AFTER_LINES),
                traceDir
        );
    }

    public CapturingExecutionEventSink captureSink() {
        return captureSink;
    }

    public Path outputDir() {
        return outputDir;
    }

    public long droppedCount() {
        return jsonlSink.getDroppedCount();
    }

    @Override
    public void emit(ExecutionEvent event) {
        captureSink.emit(event);
        jsonlSink.emit(event);
    }

    @Override
    public void emitIfEnabled(ExecutionEvent event) {
        captureSink.emitIfEnabled(event);
        jsonlSink.emitIfEnabled(event);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            jsonlSink.close();
        }
    }
}
