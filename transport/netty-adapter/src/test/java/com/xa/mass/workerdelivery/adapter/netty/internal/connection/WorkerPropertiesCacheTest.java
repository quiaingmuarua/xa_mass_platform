package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.adapter.netty.NettyWorkerObservationCacheConfig;
import java.time.Duration;
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
    void observationsHaveAdapterEpochAndInstanceMonotonicRevision() {
        AtomicLong wall = new AtomicLong(1_000L);
        AtomicLong monotonic = new AtomicLong(10L);
        WorkerPropertiesCache cache = cache(
                DEFAULT_BUDGET,
                wall::get,
                monotonic::get
        );

        cache.observe(WORKER_ID, Map.of("battery", 87L));
        var first = cache.observation(WORKER_ID);
        cache.observe("worker-2", Map.of("battery", 50L));
        wall.incrementAndGet();
        monotonic.incrementAndGet();
        cache.observe(WORKER_ID, Map.of("battery", 88L));
        var third = cache.observation(WORKER_ID);

        assertThat(first.version()).isEqualTo(
                new WorkerPropertiesObservation.Version("epoch-1", 1L)
        );
        assertThat(third.version()).isEqualTo(
                new WorkerPropertiesObservation.Version("epoch-1", 3L)
        );
        assertThat(third.observedAtMillis()).isEqualTo(1_001L);
    }

    @Test
    void stalePropertiesRemainUntilCapacityEvictionOrClear() {
        AtomicLong monotonic = new AtomicLong();
        WorkerPropertiesCache cache = cache(
                DEFAULT_BUDGET,
                () -> 123L,
                monotonic::get
        );
        cache.observe(WORKER_ID, Map.of("battery", 87L));

        monotonic.set(Duration.ofDays(365).toNanos());
        var stale = cache.observation(WORKER_ID);

        assertThat(stale.freshness()).isEqualTo(
                WorkerPropertiesObservation.Freshness.STALE
        );
        assertThat(stale.properties()).containsEntry("battery", 87L);
    }

    @Test
    void propertiesAreDefensivelyCapturedAndReturned() {
        WorkerPropertiesCache cache = cache(
                DEFAULT_BUDGET,
                () -> 123L,
                () -> 456L
        );
        List<Object> tags = new ArrayList<>(List.of("mobile"));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("tags", tags);

        cache.observe(WORKER_ID, properties);
        tags.add("changed-after-observe");
        properties.put("battery", 1L);

        var observed = cache.observation(WORKER_ID);
        assertThat(observed.properties()).containsOnlyKeys("tags");
        assertThat(observed.properties().get("tags"))
                .isEqualTo(List.of("mobile"));
    }

    @Test
    void encodedBudgetEvictsEntriesAndRevisionIsNeverReused() {
        WorkerPropertiesCache cache = cache(
                64L,
                () -> 123L,
                () -> 456L
        );
        for (int index = 0; index < 100; index++) {
            cache.observe("worker-" + index, Map.of("value", index));
        }

        String evictedWorker = null;
        for (int index = 0; index < 100; index++) {
            String workerId = "worker-" + index;
            if (cache.observation(workerId).freshness()
                    == WorkerPropertiesObservation.Freshness.UNKNOWN) {
                evictedWorker = workerId;
                break;
            }
        }
        assertThat(evictedWorker).isNotNull();

        WorkerPropertiesObservation rewritten = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            cache.observe(evictedWorker, Map.of("value", "rewritten"));
            WorkerPropertiesObservation candidate =
                    cache.observation(evictedWorker);
            if (candidate.freshness()
                    != WorkerPropertiesObservation.Freshness.UNKNOWN) {
                rewritten = candidate;
                break;
            }
        }
        assertThat(rewritten).isNotNull();
        assertThat(rewritten.version().revision()).isGreaterThan(100L);
    }

    @Test
    void staleRouteWriteCanRollbackWithoutRemovingANewerObservation() {
        WorkerPropertiesCache cache = cache(
                DEFAULT_BUDGET,
                () -> 123L,
                () -> 456L
        );
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
    void clearDropsPropertiesWithoutChangingAdapterEpoch() {
        WorkerPropertiesCache first = new WorkerPropertiesCache(config(
                DEFAULT_BUDGET
        ));
        WorkerPropertiesCache second = new WorkerPropertiesCache(config(
                DEFAULT_BUDGET
        ));
        first.observe(WORKER_ID, Map.of());

        assertThat(first.adapterEpoch()).isNotEqualTo(second.adapterEpoch());
        assertThat(first.observation("other-worker").freshness())
                .isEqualTo(WorkerPropertiesObservation.Freshness.UNKNOWN);
        first.clear();
        WorkerPropertiesObservation cleared = first.observation(WORKER_ID);
        assertThat(cleared.freshness())
                .isEqualTo(WorkerPropertiesObservation.Freshness.UNKNOWN);
        assertThat(cleared.version()).isNull();
        assertThat(cleared.observedAtMillis()).isNull();
        assertThat(cleared.properties()).isNull();
    }

    @Test
    void cacheConfigurationMustBePositive() {
        assertThatThrownBy(() -> new NettyWorkerObservationCacheConfig(
                Duration.ZERO,
                DEFAULT_BUDGET
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NettyWorkerObservationCacheConfig(
                Duration.ofMinutes(5),
                0L
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static WorkerPropertiesCache cache(
            long budget,
            java.util.function.LongSupplier wallClock,
            java.util.function.LongSupplier monotonicClock
    ) {
        return new WorkerPropertiesCache(
                config(budget),
                "epoch-1",
                wallClock,
                monotonicClock
        );
    }

    private static NettyWorkerObservationCacheConfig config(long budget) {
        return new NettyWorkerObservationCacheConfig(
                Duration.ofMinutes(5),
                budget
        );
    }
}
