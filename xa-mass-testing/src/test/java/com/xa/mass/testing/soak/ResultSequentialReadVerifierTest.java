package com.xa.mass.testing.soak;

import com.xa.mass.sdk.model.TaskResultItemSnapshot;
import com.xa.mass.sdk.model.TaskResultWindowSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultSequentialReadVerifierTest {

    @Test
    void verifiesMultiPageSequentialRead() {
        ResultSequentialReadVerifier.ResultSequentialReadSummary summary =
                ResultSequentialReadVerifier.verify("task-1", 3, 2, (taskId, afterSeq, limit) -> {
                    if (afterSeq == 0) {
                        return window(taskId, List.of(item(1, "m1"), item(2, "m2")), 2, true, 3);
                    }
                    return window(taskId, List.of(item(3, "m3")), 3, false, 3);
                });

        assertEquals("task-1", summary.taskId());
        assertEquals(3, summary.itemCount());
        assertEquals(2, summary.pages());
        assertEquals(3, summary.lastSeq());
    }

    @Test
    void rejectsDuplicateMessageId() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ResultSequentialReadVerifier.verify("task-1", 2, 10, (taskId, afterSeq, limit) ->
                        window(taskId, List.of(item(1, "m1"), item(2, "m1")), 2, false, 2)));

        assertTrue(error.getMessage().contains("duplicate result messageId"));
    }

    @Test
    void rejectsNextAfterSeqMismatch() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ResultSequentialReadVerifier.verify("task-1", 1, 10, (taskId, afterSeq, limit) ->
                        window(taskId, List.of(item(1, "m1")), 9, false, 1)));

        assertTrue(error.getMessage().contains("nextAfterSeq must equal last item seq"));
    }

    @Test
    void rejectsEmptyHasMoreWindow() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ResultSequentialReadVerifier.verify("task-1", 1, 10, (taskId, afterSeq, limit) ->
                        window(taskId, List.of(), 0, true, 1)));

        assertTrue(error.getMessage().contains("empty result window must not report hasMore"));
    }

    private static TaskResultWindowSnapshot window(String taskId,
                                                   List<TaskResultItemSnapshot> items,
                                                   long nextAfterSeq,
                                                   boolean hasMore,
                                                   long totalVisible) {
        return new TaskResultWindowSnapshot(taskId, items, nextAfterSeq, hasMore, totalVisible);
    }

    private static TaskResultItemSnapshot item(long seq, String messageId) {
        return new TaskResultItemSnapshot(
                seq,
                messageId,
                "soak.dispatch.0",
                "SUCCESS",
                "SUCCESS",
                0,
                0,
                "worker-1",
                "batch-1",
                "attempt-1",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of("ok", true)
        );
    }
}
