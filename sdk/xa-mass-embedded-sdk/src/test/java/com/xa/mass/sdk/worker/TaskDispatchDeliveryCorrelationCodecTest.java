package com.xa.mass.sdk.worker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskDispatchDeliveryCorrelationCodecTest {

    private final TaskDispatchDeliveryCorrelationCodec codec = new TaskDispatchDeliveryCorrelationCodec();

    @Test
    void roundTripsTypedAttemptCorrelationAsOpaqueReference() {
        TaskDispatchDeliveryCorrelation correlation = new TaskDispatchDeliveryCorrelation(
                " task-1 ",
                " msg-1 ",
                " attempt-1 ",
                -1
        );

        String encoded = codec.encode(correlation);
        TaskDispatchDeliveryCorrelation decoded = codec.decode(encoded);

        assertEquals("task-1", decoded.taskId());
        assertEquals("msg-1", decoded.messageId());
        assertEquals("attempt-1", decoded.attemptId());
        assertEquals(0, decoded.attemptNo());
    }

    @Test
    void rejectsBlankCorrelationReference() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode(" "));
    }
}
