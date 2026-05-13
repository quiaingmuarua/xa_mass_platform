package com.xa.mass.sdk.model;

public final class TaskResultArchiveSnapshot {
    private final String taskId;
    private final boolean ready;
    private final String format;
    private final String contentType;
    private final String contentEncoding;
    private final long itemCount;
    private final Long byteSize;
    private final String checksum;

    public TaskResultArchiveSnapshot(String taskId,
                                     boolean ready,
                                     String format,
                                     String contentType,
                                     String contentEncoding,
                                     long itemCount,
                                     Long byteSize,
                                     String checksum) {
        this.taskId = taskId;
        this.ready = ready;
        this.format = format;
        this.contentType = contentType;
        this.contentEncoding = contentEncoding;
        this.itemCount = itemCount;
        this.byteSize = byteSize;
        this.checksum = checksum;
    }

    public String getTaskId() { return taskId; }
    public boolean isReady() { return ready; }
    public String getFormat() { return format; }
    public String getContentType() { return contentType; }
    public String getContentEncoding() { return contentEncoding; }
    public long getItemCount() { return itemCount; }
    public Long getByteSize() { return byteSize; }
    public String getChecksum() { return checksum; }
}
