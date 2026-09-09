package com.xa.mass.server.task.call;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HexFormat;
import jdk.jfr.*;

/** Owner-local, default-off evidence. No registry, business state or payload leaves this owner. */
@Name("xa.mass.TaskRpc") @Label("Task call stage") @Category("XA Mass")
@Enabled(false) @StackTrace(false)
final class TaskRpcStageEvent extends Event {
    private static final EventType TYPE = EventType.getEventType(TaskRpcStageEvent.class);
    public String stage;
    public String key;
    public long startedNanos;
    public long endedNanos;
    public long elapsedNanos;
    public int batchSize;
    public int count;
    public boolean failed;

    static long start() {
        try { return TYPE.isEnabled() ? System.nanoTime() : 0; }
        catch (RuntimeException | LinkageError ignored) { return 0; }
    }

    static void batch(long started, String stage, int size, int count, boolean failed) {
        if (started == 0) return;
        try { emit(started, System.nanoTime(), stage, "", size, count, failed); }
        catch (RuntimeException | LinkageError ignored) { /* Diagnostics never control the caller. */ }
    }

    static void items(long started, String stage, String taskId, Collection<String> ids, int count, boolean failed) {
        if (started == 0) return;
        try {
            long ended = System.nanoTime();
            emit(started, ended, stage, "", ids.size(), count, failed);
            for (String id : ids) {
                String key = sampleKey(taskId, id);
                if (key != null) emit(started, ended, stage, key, ids.size(), count, failed);
            }
        } catch (RuntimeException | LinkageError ignored) { /* Evidence failure cannot change an owner result. */ }
    }

    static String sampleKey(String taskId, String messageId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((taskId + "\0" + messageId).getBytes(StandardCharsets.UTF_8));
            return (digest[0] & 63) == 0 ? HexFormat.of().formatHex(digest) : null;
        } catch (java.security.NoSuchAlgorithmException unavailable) { return null; }
    }

    private static void emit(long started, long ended, String stage, String key, int size, int count, boolean failed) {
        var event = new TaskRpcStageEvent();
        event.stage = stage;
        event.key = key;
        event.startedNanos = started;
        event.endedNanos = ended;
        event.elapsedNanos = Math.max(0, ended - started);
        event.batchSize = size;
        event.count = count;
        event.failed = failed;
        event.commit();
    }
}

