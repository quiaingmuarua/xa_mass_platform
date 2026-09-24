package com.xa.mass.kernel.pacer.result;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import jdk.jfr.*;

/** Default-off diagnostic evidence; never a retained network watermark. */
final class WorkerEvidenceDiagnostic {
    private static final EventType TYPE = EventType.getEventType(RecordedEvent.class);
    private static final AtomicLong BATCHES = new AtomicLong();

    @Name("xa.mass.WorkerEvidenceDiagnostic") @Label("Worker network evidence")
    @Category("XA Mass") @Enabled(false) @StackTrace(false)
    static final class RecordedEvent extends Event {
        public long batchId;
        public String workerKey;
        public String stage;
        public String eventName;
        public String kind;
        public long observedAtMillis;
        public long processedAtMillis;
    }

    static long batch() {
        try { return TYPE.isEnabled() ? BATCHES.incrementAndGet() : 0; }
        catch (RuntimeException | LinkageError ignored) { return 0; }
    }

    static void record(long batch, String id, String stage, String eventName,
                       String kind, long observedAt, long processedAt) {
        if (batch == 0) return;
        try {
            var event = new RecordedEvent();
            event.batchId = batch;
            event.workerKey = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((id + "\n").getBytes(StandardCharsets.UTF_8)));
            event.stage = stage;
            event.eventName = eventName;
            event.kind = kind;
            event.observedAtMillis = observedAt;
            event.processedAtMillis = processedAt;
            event.commit();
        } catch (java.security.NoSuchAlgorithmException | RuntimeException | LinkageError ignored) {
            // Diagnostics cannot alter admission, selection, or state changes.
        }
    }
}
