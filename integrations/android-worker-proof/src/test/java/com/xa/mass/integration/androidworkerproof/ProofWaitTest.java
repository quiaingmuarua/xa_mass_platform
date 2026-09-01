package com.xa.mass.integration.androidworkerproof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ProofWaitTest {

    @Test
    void retriesOnlyTransientObservationFailures() {
        AtomicInteger observations = new AtomicInteger();

        String result = ProofWait.until(
                Duration.ofSeconds(1L),
                () -> {
                    if (observations.incrementAndGet() == 1) {
                        throw new TransientObservationFailure(
                                "temporarily unavailable",
                                new IOException("closed")
                        );
                    }
                    return "ready";
                },
                "ready"::equals,
                "wait.transient",
                "Observation did not recover",
                null
        );

        assertEquals("ready", result);
        assertEquals(2, observations.get());
    }

    @Test
    void failsImmediatelyForContractFailures() {
        AtomicInteger observations = new AtomicInteger();

        ProofFailure failure = assertThrows(ProofFailure.class, () ->
                ProofWait.until(
                        Duration.ofSeconds(1L),
                        () -> {
                            observations.incrementAndGet();
                            throw new ProofFailure(
                                    "observation.contract",
                                    "Invalid observation"
                            );
                        },
                        ignored -> false,
                        "wait.contract",
                        "Contract failure was hidden",
                        null
                ));

        assertEquals("observation.contract", failure.invariant());
        assertEquals(1, observations.get());
    }
}
