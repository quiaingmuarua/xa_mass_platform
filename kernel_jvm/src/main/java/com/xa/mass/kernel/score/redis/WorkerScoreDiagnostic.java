package com.xa.mass.kernel.score.redis;

import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** Default-off Score Owner evidence from existing replies, with no additional Redis reads. */
final class WorkerScoreDiagnostic {
    private static final EventType TYPE = eventType();

    @Name("xa.mass.WorkerScoreDiagnostic") @Category("XA Mass")
    @Enabled(false) @StackTrace(false)
    static final class RecordedEvent extends Event {
        public String workerKey;
        public String operation;
        public long startedEpochMillis;
        public long endedEpochMillis;
        public String status;
        public String polarity = "";
        public long timeMillis = -1;
        public int mark = -1;
        public long evidenceMillis = -1;
        public String targetPolarity = "";
    }

    private static EventType eventType() {
        try { return EventType.getEventType(RecordedEvent.class); }
        catch (RuntimeException | LinkageError ignored) { return null; }
    }

    static long start() {
        try { return TYPE != null && TYPE.isEnabled() ? System.currentTimeMillis() : 0; }
        catch (RuntimeException | LinkageError ignored) { return 0; }
    }

    static void batch(long started, String operation,
            Map<String, WorkerScoreTransitionResult> results,
            Map<String, Long> evidence, WorkerScorePolarity target) {
        if (started == 0) return;
        try {
            long ended = System.currentTimeMillis();
            results.forEach((id, result) -> emit(started, ended, operation, id, result,
                    evidence == null ? null : evidence.get(id), target));
        } catch (RuntimeException | LinkageError ignored) { /* Evidence cannot change an Owner result. */ }
    }

    static void single(long started, String operation, String id, WorkerScoreTransitionResult result) {
        if (started == 0) return;
        try { emit(started, System.currentTimeMillis(), operation, id, result, null, null); }
        catch (RuntimeException | LinkageError ignored) { /* Evidence cannot change an Owner result. */ }
    }

    private static void emit(long started, long ended, String operation, String id,
            WorkerScoreTransitionResult result, Long evidence, WorkerScorePolarity target) {
        try {
            var event = new RecordedEvent();
            event.workerKey = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((id + "\n").getBytes(StandardCharsets.UTF_8)));
            event.operation = operation;
            event.startedEpochMillis = started;
            event.endedEpochMillis = ended;
            event.status = result.status().name();
            if (result.score() != null) {
                try {
                    var state = WorkerScoreEncoding.decodeState(id, result.score().doubleValue());
                    event.polarity = state.polarity().name();
                    event.timeMillis = state.timeMillis();
                    event.mark = state.mark();
                } catch (RuntimeException ignored) { /* Retain the status even when coordinates are invalid. */ }
            }
            if (evidence != null) event.evidenceMillis = evidence;
            if (target != null) event.targetPolarity = target.name();
            event.commit();
        } catch (java.security.NoSuchAlgorithmException | RuntimeException | LinkageError ignored) {
            // An invalid diagnostic reply must not change the returned result or later evidence.
        }
    }
}
