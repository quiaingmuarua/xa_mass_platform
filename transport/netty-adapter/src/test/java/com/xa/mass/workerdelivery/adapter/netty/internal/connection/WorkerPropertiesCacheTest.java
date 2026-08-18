package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.adapter.netty.NettyWorkerPropertiesCacheConfig;
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
    void updateTimeIsStrictlyIncreasingWithinRetainedEntry() {
        AtomicLong wallClock = new AtomicLong(1_000L);
        WorkerPropertiesCache cache = cache(DEFAULT_BUDGET, wallClock::get);

        cache.observe(WORKER_ID, Map.of("battery", 87L));
        long first = cache.observation(WORKER_ID).updatedAtMillis();
        cache.observe(WORKER_ID, Map.of("battery", 88L));
        long sameMillisecond = cache.observation(WORKER_ID).updatedAtMillis();
        wallClock.set(900L);
        cache.observe(WORKER_ID, Map.of("battery", 89L));
        long clockMovedBack = cache.observation(WORKER_ID).updatedAtMillis();

        assertThat(first).isEqualTo(1_000L);
        assertThat(sameMillisecond).isEqualTo(1_001L);
        assertThat(clockMovedBack).isEqualTo(1_002L);
    }

    @Test
    void propertiesAreDefensivelyCapturedAndReturned() {
        WorkerPropertiesCache cache = cache(DEFAULT_BUDGET, () -> 123L);
        List<Object> tags = new ArrayList<>(List.of("mobile"));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("tags", tags);

        cache.observe(WORKER_ID, properties);
        tags.add("changed-after-observe");
        properties.put("battery", 1L);

        var observed = cache.observation(WORKER_ID);
        assertThat(observed.updatedAtMillis()).isEqualTo(123L);
        assertThat(observed.properties()).containsOnlyKeys("tags");
        assertThat(observed.properties().get("tags"))
                .isEqualTo(List.of("mobile"));
        assertThatThrownBy(() -> observed.properties().put("other", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void encodedBudgetEvictsEntriesAndAllowsANewBaseline() {
        AtomicLong wallClock = new AtomicLong(123L);
        WorkerPropertiesCache cache = cache(64L, wallClock::get);
        for (int index = 0; index < 100; index++) {
            cache.observe("worker-" + index, Map.of("value", index));
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
        cache.observe(WORKER_ID, Map.of("battery", 87L));
        WorkerPropertiesCache.ObservationWrite stale = cache.observe(
                WORKER_ID,
                Map.of("battery", 1L)
        );
        cache.rollback(stale);
        assertThat(cache.observation(WORKER_ID).properties())
                .containsEntry("battery", 87L);

        WorkerPropertiesCache.ObservationWrite superseded = cache.observe(
                WORKER_ID,
                Map.of("battery", 2L)
        );
        cache.observe(WORKER_ID, Map.of("battery", 3L));
        cache.rollback(superseded);
        assertThat(cache.observation(WORKER_ID).properties())
                .containsEntry("battery", 3L);
    }

    @Test
    void invalidateAndClearReturnUnknownProjection() {
        WorkerPropertiesCache cache = cache(DEFAULT_BUDGET, () -> 123L);
        cache.observe(WORKER_ID, Map.of("battery", 87L));

        cache.invalidate(WORKER_ID);
        assertUnknown(cache.observation(WORKER_ID));

        cache.observe(WORKER_ID, Map.of("battery", 88L));
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

    @Test
    void cacheConfigurationMustBePositive() {
        assertThatThrownBy(() -> new NettyWorkerPropertiesCacheConfig(0L))
                .isInstanceOf(IllegalArgumentException.class);
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
                new NettyWorkerPropertiesCacheConfig(budget),
                wallClock
        );
    }
}
