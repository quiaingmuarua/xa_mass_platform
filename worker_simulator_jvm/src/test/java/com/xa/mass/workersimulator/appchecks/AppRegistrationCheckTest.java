package com.xa.mass.workersimulator.appchecks;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AppRegistrationCheckTest {
    static Map<String, Object> ranges(int registered, int unregistered) {
        return Map.of("registered", List.of(0, registered), "unregistered", List.of(registered, unregistered),
                "failed", List.of(unregistered, 1000));
    }
    static Map<String, Object> input(Object ranges, Object delay) {
        var input = new HashMap<String, Object>();
        input.put("number", "+8613800000001"); input.put("salt", "fixed-salt");
        input.put("ranges", ranges); input.put("delayMs", delay);
        return input;
    }

    @Test void independentPythonVectorsFixUnsignedHashEncodingAndDelayDomain() {
        var first = AppRegistrationCheck.plan("worker-a", "fixed-salt", "+8613800000001", ranges(500, 900), List.of(2000, 5000));
        var second = AppRegistrationCheck.plan("worker-b", "fixed-salt", "+8613800000001", ranges(500, 900), List.of(2000, 5000));
        assertThat(first).isEqualTo(new AppRegistrationCheck.Plan("registered", 25, 4067));
        assertThat(second).isEqualTo(new AppRegistrationCheck.Plan("failed", 917, 4911));
        assertThat(AppRegistrationCheck.plan("worker-a", "fixed-salt", "+8613800000001", ranges(500, 900), List.of(2000, 5000)))
                .isEqualTo(first);
    }

    @Test void halfOpenBoundaryAndEmptyRangesCoverAllThreeOutcomes() {
        for (var test : List.of(Map.entry(ranges(26, 900), "registered"), Map.entry(ranges(25, 900), "unregistered"),
                Map.entry(ranges(0, 25), "failed"), Map.entry(ranges(1000, 1000), "registered"),
                Map.entry(ranges(0, 1000), "unregistered"), Map.entry(ranges(0, 0), "failed"))) {
            var plan = AppRegistrationCheck.plan("worker-a", "fixed-salt", "+8613800000001", test.getKey(), List.of(0, 0));
            assertThat(plan.outcome()).isEqualTo(test.getValue());
            assertThat(plan.delayMillis()).isZero();
        }
    }

    @Test void unregisterIsSuccessAndIdentityComesFromTheExecutingReplica() throws Exception {
        var reads = new AtomicInteger();
        var definition = AppRegistrationCheck.definition("app-b-sim", () -> "actual-" + reads.incrementAndGet());
        var result = Jsons.parseObject(definition.handler().execute(input(ranges(0, 1000), List.of(0, 0))));
        assertThat(result).containsEntry("registered", false).containsEntry("workerId", "actual-1")
                .containsEntry("workerGroupId", "app-b-sim").containsEntry("number", "+8613800000001");
        assertThat(reads).hasValue(1);
        assertThat(definition.eventName()).isEqualTo(AppRegistrationCheck.EVENT);
    }

    @Test void failureThrowsRatherThanReturningABusinessAnswer() {
        assertThatThrownBy(() -> AppRegistrationCheck.execute("app-a-sim", "worker-a", input(ranges(0, 0), List.of(0, 0))))
                .isInstanceOfSatisfying(WorkerException.class, error -> assertThat(error.errorCode()).isEqualTo(WorkerErrorCode.EVENT_EXECUTION_FAILED));
    }

    @Test void invalidDescriptionsAreInputFailures() {
        var descriptions = new ArrayList<Map<String, Object>>();
        descriptions.add(input(Map.of("registered", List.of(0, 501), "unregistered", List.of(500, 900), "failed", List.of(900, 1000)), List.of(0, 0)));
        descriptions.add(input(Map.of("registered", List.of(0, 499), "unregistered", List.of(500, 900), "failed", List.of(900, 1000)), List.of(0, 0)));
        for (Object delay : Arrays.asList(null, List.of(-1, 0), List.of(1, 0), List.of(0, 30001), List.of(0.5, 1), List.of(0)))
            descriptions.add(input(ranges(500, 900), delay));
        var forged = input(ranges(500, 900), List.of(0, 0)); forged.put("workerId", "forged"); descriptions.add(forged);
        var missing = input(ranges(500, 900), List.of(0, 0)); missing.remove("salt"); descriptions.add(missing);
        for (var value : descriptions) assertThatThrownBy(() -> AppRegistrationCheck.execute("app-a-sim", "worker-a", value))
                .isInstanceOfSatisfying(WorkerException.class, error -> assertThat(error.errorCode()).isEqualTo(WorkerErrorCode.EVENT_INPUT_INVALID));
    }

    @Test void delayInterruptionRemainsAnExecutionFailureWithInterruptFlag() {
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> AppRegistrationCheck.execute("app-a-sim", "worker-a", input(ranges(1000, 1000), List.of(30_000, 30_000))))
                    .isInstanceOfSatisfying(WorkerException.class, error -> assertThat(error.errorCode()).isEqualTo(WorkerErrorCode.EVENT_EXECUTION_FAILED));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }
}
