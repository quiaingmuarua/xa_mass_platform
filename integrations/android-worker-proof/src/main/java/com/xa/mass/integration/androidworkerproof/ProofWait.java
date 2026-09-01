package com.xa.mass.integration.androidworkerproof;

import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

final class ProofWait {

    private ProofWait() {
    }

    static <T> T until(
            Duration maximumWait,
            Supplier<T> observation,
            Predicate<T> accepted,
            String invariant,
            String failureMessage,
            String identity
    ) {
        long deadline = System.nanoTime() + maximumWait.toNanos();
        RuntimeException lastFailure = null;
        T latest = null;
        while (System.nanoTime() < deadline) {
            try {
                latest = observation.get();
                if (accepted.test(latest)) {
                    return latest;
                }
            } catch (RuntimeException error) {
                lastFailure = error;
            }
            sleep();
        }
        List<String> inconsistent = identity == null
                ? List.of()
                : List.of(identity);
        ProofFailure failure = new ProofFailure(
                invariant,
                failureMessage,
                List.of(),
                List.of(),
                inconsistent
        );
        if (lastFailure != null) {
            failure.addSuppressed(lastFailure);
        }
        return fail(failure);
    }

    static void observeFor(
            Duration duration,
            Runnable assertion,
            String invariant,
            String failureMessage
    ) {
        long deadline = System.nanoTime() + duration.toNanos();
        int samples = 0;
        while (System.nanoTime() < deadline) {
            try {
                assertion.run();
            } catch (RuntimeException error) {
                throw new ProofFailure(
                        invariant,
                        failureMessage,
                        error
                );
            }
            samples++;
            sleep();
        }
        if (samples == 0) {
            throw new ProofFailure(invariant, failureMessage);
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(100L);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ProofFailure(
                    "proof.interrupted",
                    "Android Worker proof was interrupted",
                    error
            );
        }
    }

    private static <T> T fail(ProofFailure failure) {
        throw failure;
    }
}
