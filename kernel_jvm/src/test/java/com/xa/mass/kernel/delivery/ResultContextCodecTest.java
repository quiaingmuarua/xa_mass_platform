package com.xa.mass.kernel.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ResultContextCodecTest {

    private final ResultContextCodec codec = new ResultContextCodec();

    @Test
    void decodesGoldenContextAndIgnoresUnknownFields() {
        var decoded = codec.decode("""
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
        assertEquals(123L, decoded.workerLeaseScore());
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
            assertTrue(codec.decode(value).isEmpty(), value);
        }
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
