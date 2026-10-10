package com.xa.mass.workersimulator.messaging;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/** One synchronous external attempt. An unconfirmed effect must never be retried here. */
@FunctionalInterface
interface MessageSendOperation {
    Acceptance send(Map<String, Object> message, Map<String, Object> sender,
            String callbackId, Duration remainingBudget) throws IOException, InterruptedException, Rejected;

    record Acceptance(Map<String, Object> snapshot, String callbackId) {}

    /** Definitive non-acceptance. Input rejection retains the existing Handler classification. */
    final class Rejected extends Exception {
        final boolean invalidInput;
        Rejected(boolean invalidInput, String message) { super(message); this.invalidInput = invalidInput; }
    }
}
