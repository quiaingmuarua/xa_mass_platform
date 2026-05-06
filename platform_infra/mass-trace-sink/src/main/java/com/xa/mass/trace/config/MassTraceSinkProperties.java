package com.xa.mass.trace.config;

import com.xa.mass.trace.sink.OverflowPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mass.trace.sink")
public class MassTraceSinkProperties {

    /** Enable the JSONL sink. Defaults to disabled (no-op). */
    private boolean enabled = false;

    /** Directory where JSONL event files are written. */
    private String outputDir = "trace-events";

    /** Maximum events held in the in-memory queue before drops begin. */
    private int queueCapacity = 4096;

    /** Rotate to a new file after this many lines have been written. */
    private int rotateAfterLines = 100_000;

    /**
     * What to do when the queue is full.
     * {@code DROP} (default) silently discards the event.
     * {@code FALLBACK_SYNC} writes synchronously on the caller thread — debug only.
     */
    private OverflowPolicy overflowPolicy = OverflowPolicy.DROP;

    /**
     * How long (ms) {@code close()} waits for the background writer to drain before
     * logging a warning and returning.  Default: 5000 ms.
     */
    private long shutdownDrainTimeoutMs = 5_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }

    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }

    public int getRotateAfterLines() { return rotateAfterLines; }
    public void setRotateAfterLines(int rotateAfterLines) { this.rotateAfterLines = rotateAfterLines; }

    public OverflowPolicy getOverflowPolicy() { return overflowPolicy; }
    public void setOverflowPolicy(OverflowPolicy overflowPolicy) { this.overflowPolicy = overflowPolicy; }

    public long getShutdownDrainTimeoutMs() { return shutdownDrainTimeoutMs; }
    public void setShutdownDrainTimeoutMs(long shutdownDrainTimeoutMs) { this.shutdownDrainTimeoutMs = shutdownDrainTimeoutMs; }
}
