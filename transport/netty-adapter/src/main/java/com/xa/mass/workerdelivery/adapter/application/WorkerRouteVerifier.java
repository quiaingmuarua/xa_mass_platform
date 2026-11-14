package com.xa.mass.workerdelivery.adapter.application;

import java.util.concurrent.CompletionStage;
import java.util.Objects;

/** Asynchronous Server-owned verification port for one claimed Worker route. */
@FunctionalInterface
public interface WorkerRouteVerifier {

    CompletionStage<Decision> verify(String adapterId, String workerId);

    record Decision(Status status, Long cooldownUntilMillis) {
        public static final Decision VERIFIED = new Decision(Status.VERIFIED, null);
        public static final Decision REJECTED = new Decision(Status.REJECTED, null);

        public Decision {
            Objects.requireNonNull(status, "status");
            if ((status == Status.COOLDOWN) != (cooldownUntilMillis != null)
                    || (cooldownUntilMillis != null && cooldownUntilMillis <= 0)) {
                throw new IllegalArgumentException("Cooldown requires a positive deadline only");
            }
        }

        public static Decision cooldown(long untilMillis) {
            return new Decision(Status.COOLDOWN, untilMillis);
        }
    }

    enum Status {
        VERIFIED, REJECTED, COOLDOWN
    }
}
