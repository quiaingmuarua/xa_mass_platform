package com.xa.mass.workerdelivery.protocol;

import static org.junit.jupiter.api.Assertions.*;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.TaskOutcomeObservation;
import org.junit.jupiter.api.Test;

class TaskOutcomeObservationCodecTest {
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    @Test
    void stateOnlyAndOpaqueContentRoundTrip() {
        for (int tag = 6; tag <= 9; tag++) {
            for (String content : new String[]{null, "opaque reply"}) {
                String encoded = codec.encodeTaskOutcomeObservation(new TaskOutcomeObservation(tag, 1001, content));
                var decoded = codec.decodeTaskOutcomeObservation(encoded);
                assertNotNull(decoded);
                assertEquals(tag, decoded.tag());
                assertEquals(1001, decoded.observedAtMillis());
                assertEquals(content, decoded.opaqueResultPayload());
                assertEquals(content != null, encoded.contains("opaqueResultPayload"));
            }
        }
    }

    @Test
    void malformedAndUnrecognizedFieldsAreRejected() {
        for (String payload : new String[]{"null", "[]", "{", "{}",
                "{\"tag\":5,\"observedAtMillis\":1000}",
                "{\"tag\":10,\"observedAtMillis\":1000}",
                "{\"tag\":9.1,\"observedAtMillis\":1000}",
                "{\"tag\":9,\"observedAtMillis\":0}",
                "{\"tag\":9,\"observedAtMillis\":1.2}",
                "{\"tag\":9,\"observedAtMillis\":1000,\"opaqueResultPayload\":null}",
                "{\"tag\":9,\"observedAtMillis\":1000,\"opaqueResultPayload\":\" \"}",
                "{\"tag\":9,\"observedAtMillis\":1000,\"opaqueResultPayload\":\"\u2003\"}",
                "{\"tag\":9,\"observedAtMillis\":1000,\"extra\":1}"}) {
            assertNull(codec.decodeTaskOutcomeObservation(payload), payload);
        }
    }
}
