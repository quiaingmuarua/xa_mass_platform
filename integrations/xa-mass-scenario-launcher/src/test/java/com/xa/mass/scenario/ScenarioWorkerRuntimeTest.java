package com.xa.mass.scenario;

import com.xa.mass.client.worker.WorkerEventBindingSpec;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioWorkerRuntimeTest {
    @Test
    void selectsOnlyPollingLaunchWorkersWithCap() {
        List<WorkerScenarioSpec> selected = ScenarioWorkerRuntime.launchablePollingSpecs(List.of(
                worker("ws-worker", "websocket", "realtime", null),
                worker("socket-worker", "socket", "realtime", null),
                worker("api-worker-1", "polling", "polling", "api-online"),
                worker("api-worker-2", "polling", "polling", "api-online")
        ), 1);

        assertEquals(1, selected.size());
        assertEquals("api-worker-1", selected.getFirst().workerId());
    }

    @Test
    void zeroCapMeansNoLaunchLimit() {
        List<WorkerScenarioSpec> selected = ScenarioWorkerRuntime.launchablePollingSpecs(List.of(
                worker("api-worker-1", "polling", "polling", "api-online"),
                worker("api-worker-2", "polling", "polling", "api-online")
        ), 0);

        assertEquals(2, selected.size());
    }

    @Test
    void selectsOnlyWebSocketLaunchWorkersWhenEndpointExists() {
        List<WorkerScenarioSpec> selected = ScenarioWorkerRuntime.launchableWebSocketSpecs(List.of(
                worker("ws-worker-1", "websocket", "realtime", null),
                worker("ws-worker-2", "websocket", null, "websocket"),
                worker("socket-worker", "socket", "realtime", null),
                worker("api-worker", "polling", "polling", "api-online")
        ), true);

        assertEquals(2, selected.size());
        assertEquals("ws-worker-1", selected.get(0).workerId());
        assertEquals("ws-worker-2", selected.get(1).workerId());
    }

    @Test
    void websocketLaunchRequiresEndpoint() {
        List<WorkerScenarioSpec> selected = ScenarioWorkerRuntime.launchableWebSocketSpecs(List.of(
                worker("ws-worker", "websocket", "realtime", null)
        ), false);

        assertTrue(selected.isEmpty());
    }

    @Test
    void idleTrackerUsesPositiveTimeoutOnly() {
        MutableClock clock = new MutableClock(1_000L);
        ScenarioIdleTracker tracker = new ScenarioIdleTracker(clock);

        assertFalse(tracker.isIdleFor(0));
        clock.currentMillis = 1_500L;
        assertFalse(tracker.isIdleFor(600));
        clock.currentMillis = 1_601L;
        assertTrue(tracker.isIdleFor(600));
        tracker.markActivity();
        assertFalse(tracker.isIdleFor(600));
    }

    private static WorkerScenarioSpec worker(String workerId, String adapterId, String transportHint, String startMode) {
        return new WorkerScenarioSpec(
                workerId,
                workerId + "-key",
                "sample-group",
                "sample-node",
                adapterId,
                transportHint,
                startMode,
                Map.of("region", "sg"),
                List.of(WorkerEventBindingSpec.of("probe.phone.metadata", List.of("deviceProbe")))
        );
    }

    private static final class MutableClock extends Clock {
        private long currentMillis;

        private MutableClock(long currentMillis) {
            this.currentMillis = currentMillis;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(currentMillis);
        }

        @Override
        public long millis() {
            return currentMillis;
        }
    }
}
