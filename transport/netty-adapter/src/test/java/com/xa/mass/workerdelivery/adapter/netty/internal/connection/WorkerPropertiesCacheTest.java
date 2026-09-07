package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class WorkerPropertiesCacheTest {

    private static final String WORKER_ID = "worker-1";
    private static final long DEFAULT_BUDGET = 64L * 1024L * 1024L;

    @Test
    void patchNeedsBaselineAndFullReplacesAllPreviousKeys() {
        WorkerPropertiesCache cache = cache(DEFAULT_BUDGET, () -> 123L);
        assertThat(cache.patch(WORKER_ID, Map.of("battery", "90"), List.of())).isNull();
        assertUnknown(cache.observation(WORKER_ID));
        cache.observe(WORKER_ID, Map.of(
                "battery", "87", "network.type", "wifi", "network.ssid", "home"));
        var write = cache.patch(WORKER_ID, Map.of("network.type", "cellular", "empty", ""),
                List.of("network.ssid"));
        assertThat(cache.observation(WORKER_ID).properties()).isEqualTo(
                Map.of("battery", "87", "network.type", "cellular", "empty", ""));
        var empty = cache.patch(WORKER_ID, Map.of(), List.of());
        assertThat(empty.written().metadata().propertiesFingerprint())
                .isEqualTo(write.written().metadata().propertiesFingerprint());
        assertThat(empty.written().metadata().updatedAtMillis()).isEqualTo(125L);
        cache.observe(WORKER_ID, Map.of("new", "baseline"));
        assertThat(cache.observation(WORKER_ID).properties()).isEqualTo(Map.of("new", "baseline"));
        cache.observe(WORKER_ID, Map.of());
        assertThat(cache.observation(WORKER_ID).properties()).isEmpty();
    }

    @Test
    void canonicalFingerprintAndWeightDescribeTheCompleteImmutableValue() {
        WorkerPropertiesCache cache = cache(DEFAULT_BUDGET, () -> 123L);
        Map<String, String> first = new LinkedHashMap<>();
        first.put("z", "87");
        first.put("a", "中文");
        var original = cache.observe(WORKER_ID, first).written();
        Map<String, String> reordered = new LinkedHashMap<>();
        reordered.put("a", "中文");
        reordered.put("z", "87");
        var same = cache.observe(WORKER_ID, reordered).written();
        byte[] canonical = "{\"a\":\"中文\",\"z\":\"87\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var crc = new java.util.zip.CRC32C();
        crc.update(canonical, 0, canonical.length);
        assertThat(same.metadata().propertiesFingerprint()).isEqualTo(crc.getValue())
                .isEqualTo(original.metadata().propertiesFingerprint());
        assertThat(same.encodedWeight()).isEqualTo(canonical.length + WORKER_ID.length())
                .isEqualTo(original.encodedWeight());
        var changed = cache.patch(WORKER_ID, Map.of("z", "longer"), List.of());
        assertThat(changed.written().metadata().propertiesFingerprint())
                .isNotEqualTo(same.metadata().propertiesFingerprint());
        assertThat(changed.written().encodedWeight()).isGreaterThan(same.encodedWeight());
        var deleted = cache.patch(WORKER_ID, Map.of(), List.of("z"));
        assertThat(deleted.written().encodedWeight()).isLessThan(changed.written().encodedWeight());
    }

    @Test
    void concurrentPatchesDoNotLoseIndependentFieldsAndStaleRollbackIsConditional() throws Exception {
        WorkerPropertiesCache cache = cache(DEFAULT_BUDGET, () -> 123L);
        cache.observe(WORKER_ID, Map.of());
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                String key = "field-" + index;
                futures.add(executor.submit(() -> {
                    start.await();
                    cache.patch(WORKER_ID, Map.of(key, "true"), List.of());
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
        assertThat(cache.observation(WORKER_ID).properties()).hasSize(100);
        assertThat(cache.observation(WORKER_ID).updatedAtMillis()).isEqualTo(223L);
        var stale = cache.patch(WORKER_ID, Map.of("stale", "true"), List.of());
        var newer = cache.patch(WORKER_ID, Map.of("new", "true"), List.of("stale"));
        cache.rollback(stale);
        var recaptured = cache.observe(WORKER_ID, cache.observation(WORKER_ID).properties());
        assertThat(recaptured.written().metadata().propertiesFingerprint())
                .isEqualTo(newer.written().metadata().propertiesFingerprint());
        assertThat(recaptured.written().encodedWeight()).isEqualTo(newer.written().encodedWeight());
        assertThat(cache.observation(WORKER_ID).properties()).containsEntry("new", "true")
                .doesNotContainKey("stale");
    }

    @Test
    void invalidPatchCannotPartiallyChangeBaseline() {
        WorkerPropertiesCache cache = cache(DEFAULT_BUDGET, () -> 123L);
        cache.observe(WORKER_ID, Map.of("battery", "87"));
        assertThatThrownBy(() -> cache.patch(WORKER_ID, Map.of("battery", "88"), List.of("battery")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cache.patch(WORKER_ID, Map.of(), List.of("battery", "battery")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(cache.observation(WORKER_ID).properties()).containsEntry("battery", "87");
        assertThat(cache.observation(WORKER_ID).updatedAtMillis()).isEqualTo(123L);
    }

    @Test
    void updateTimeIsStrictlyIncreasingWithinRetainedEntry() {
        AtomicLong wallClock = new AtomicLong(1_000L);
        WorkerPropertiesCache cache = cache(DEFAULT_BUDGET, wallClock::get);

        cache.observe(WORKER_ID, Map.of("battery", "87"));
        long first = cache.observation(WORKER_ID).updatedAtMillis();
        cache.observe(WORKER_ID, Map.of("battery", "88"));
        long sameMillisecond = cache.observation(WORKER_ID).updatedAtMillis();
        wallClock.set(900L);
        cache.observe(WORKER_ID, Map.of("battery", "89"));
        long clockMovedBack = cache.observation(WORKER_ID).updatedAtMillis();

        assertThat(first).isEqualTo(1_000L);
        assertThat(sameMillisecond).isEqualTo(1_001L);
        assertThat(clockMovedBack).isEqualTo(1_002L);
    }

    @Test
    void propertiesAreDefensivelyCapturedAndReturned() {
        WorkerPropertiesCache cache = cache(DEFAULT_BUDGET, () -> 123L);
        Map<String, String> properties = new LinkedHashMap<>(Map.of("tags", "mobile"));
        cache.observe(WORKER_ID, properties);
        properties.put("tags", "changed");
        var observed = cache.observation(WORKER_ID);
        assertThat(observed.updatedAtMillis()).isEqualTo(123L);
        assertThat(observed.properties()).isEqualTo(Map.of("tags", "mobile"));
        assertThatThrownBy(() -> observed.properties().put("other", "true"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void encodedBudgetEvictsEntriesAndAllowsANewBaseline() {
        AtomicLong wallClock = new AtomicLong(123L);
        WorkerPropertiesCache cache = cache(64L, wallClock::get);
        for (int index = 0; index < 100; index++) {
            cache.observe("worker-" + index, Map.of("value", Integer.toString(index)));
        }

        String evictedWorker = null;
        for (int index = 0; index < 100; index++) {
            String workerId = "worker-" + index;
            if (cache.observation(workerId).updatedAtMillis() == null) {
                evictedWorker = workerId;
                break;
            }
        }
        assertThat(evictedWorker).isNotNull();
        assertThat(cache.patch(evictedWorker, Map.of("value", "partial"), List.of())).isNull();
        assertUnknown(cache.observation(evictedWorker));

        wallClock.set(456L);
        WorkerPropertiesObservation rewritten = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            cache.observe(evictedWorker, Map.of("value", "rewritten"));
            WorkerPropertiesObservation candidate =
                    cache.observation(evictedWorker);
            if (candidate.updatedAtMillis() != null) {
                rewritten = candidate;
                break;
            }
        }
        assertThat(rewritten).isNotNull();
        assertThat(rewritten.updatedAtMillis()).isEqualTo(456L);
    }

    @Test
    void staleRouteWriteCanRollbackWithoutRemovingANewerObservation() {
        WorkerPropertiesCache cache = cache(DEFAULT_BUDGET, () -> 123L);
        cache.observe(WORKER_ID, Map.of("battery", "87"));
        WorkerPropertiesCache.ObservationWrite stale = cache.observe(
                WORKER_ID,
                Map.of("battery", "1")
        );
        cache.rollback(stale);
        assertThat(cache.observation(WORKER_ID).properties())
                .containsEntry("battery", "87");

        WorkerPropertiesCache.ObservationWrite superseded = cache.observe(
                WORKER_ID,
                Map.of("battery", "2")
        );
        cache.observe(WORKER_ID, Map.of("battery", "3"));
        cache.rollback(superseded);
        assertThat(cache.observation(WORKER_ID).properties())
                .containsEntry("battery", "3");
    }

    @Test
    void invalidateAndClearReturnUnknownProjection() {
        WorkerPropertiesCache cache = cache(DEFAULT_BUDGET, () -> 123L);
        cache.observe(WORKER_ID, Map.of("battery", "87"));

        cache.invalidate(WORKER_ID);
        assertUnknown(cache.observation(WORKER_ID));

        cache.observe(WORKER_ID, Map.of("battery", "88"));
        cache.clear();
        assertUnknown(cache.observation(WORKER_ID));
    }

    @Test
    void observationRequiresBothUpdateTimeAndProperties() {
        assertThatThrownBy(() -> new WorkerPropertiesObservation(
                123L,
                null
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerPropertiesObservation(
                null,
                Map.of()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertUnknown(WorkerPropertiesObservation value) {
        assertThat(value.updatedAtMillis()).isNull();
        assertThat(value.properties()).isNull();
    }

    private static WorkerPropertiesCache cache(
            long budget,
            java.util.function.LongSupplier wallClock
    ) {
        return new WorkerPropertiesCache(
                budget,
                wallClock
        );
    }
}
