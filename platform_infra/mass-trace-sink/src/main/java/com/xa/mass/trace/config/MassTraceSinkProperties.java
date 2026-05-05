package com.xa.mass.trace.config;

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

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }

    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }

    public int getRotateAfterLines() { return rotateAfterLines; }
    public void setRotateAfterLines(int rotateAfterLines) { this.rotateAfterLines = rotateAfterLines; }
}
