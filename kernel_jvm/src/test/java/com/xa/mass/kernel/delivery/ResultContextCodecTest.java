package com.xa.mass.kernel.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.kernel.worker.WorkerLeaseReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ResultContextCodecTest {

    private final ResultContextCodec codec = new ResultContextCodec();

    @Test
    void decodesGoldenContextAndIgnoresUnknownFields() {
        var decoded = codec.decodeForRouting("""
                {
                  "taskId":"task-1",
                  "messageId":"message-1",
                  "workerId":"worker-1",
                  "workerGroupId":"group-1",
                  "workerLeaseScore":123,
                  "futureField":true
                }
                """).orElseThrow();

        assertEquals("task-1", decoded.taskId());
        assertEquals("message-1", decoded.messageId());
        assertEquals("worker-1", decoded.workerId());
        assertEquals("group-1", decoded.workerGroupId());
        assertEquals(
                WorkerLeaseReference.fromEncodedScore(123),
                decoded.workerLease()
        );
        assertEquals(
                "WorkerLeaseReference[opaque]",
                decoded.workerLease().toString()
        );
    }

    @Test
    void rejectsMalformedMissingAndNonPositiveOrNonIntegralScores() {
        for (String value : new String[]{
                "not-json",
                "{}",
                context("0"),
                context("-1"),
                context("1.5"),
                context("true"),
                context("\"1\"")
        }) {
            assertTrue(codec.decodeForRouting(value).isEmpty(), value);
        }
    }

    @Test
    void routedLeaseReferenceExposesNoNumericAccessor() {
        assertFalse(Arrays.stream(
                        WorkerLeaseReference.class.getDeclaredMethods()
                )
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getReturnType)
                .anyMatch(type -> type == long.class
                        || type == Long.class));
    }

    private static String context(String score) {
        return """
                {
                  "taskId":"task-1",
                  "messageId":"message-1",
                  "workerId":"worker-1",
                  "workerGroupId":"group-1",
                  "workerLeaseScore":%s
                }
                """.formatted(score);
    }
}
