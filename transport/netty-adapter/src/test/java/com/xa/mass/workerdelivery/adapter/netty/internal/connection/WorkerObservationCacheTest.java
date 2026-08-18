package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class WorkerObservationCacheTest {

    private static final String WORKER_ID = "worker-1";

    @Test
    void observationsHaveAdapterEpochAndPerWorkerMonotonicRevision() {
        AtomicLong wall = new AtomicLong(1_000L);
        AtomicLong monotonic = new AtomicLong(10L);
        WorkerObservationCache cache = new WorkerObservationCache(
                Duration.ofMinutes(5),
                "epoch-1",
                wall::get,
                monotonic::get
        );

        cache.observe(WORKER_ID, Map.of("battery", 87L));
        var first = cache.observation(WORKER_ID);
        wall.incrementAndGet();
        monotonic.incrementAndGet();
        cache.observe(WORKER_ID, Map.of("battery", 87L));
        var second = cache.observation(WORKER_ID);

        assertThat(first.version()).isEqualTo(
                new WorkerObservationSnapshot.PropertiesVersion(
                        "epoch-1",
                        1L
                )
        );
        assertThat(second.version()).isEqualTo(
                new WorkerObservationSnapshot.PropertiesVersion(
                        "epoch-1",
                        2L
                )
        );
        assertThat(second.observedAtMillis()).isEqualTo(1_001L);
    }

    @Test
    void freshnessUsesMonotonicBoundaryAndStaleKeepsProperties() {
        AtomicLong monotonic = new AtomicLong(0L);
        WorkerObservationCache cache = new WorkerObservationCache(
                Duration.ofMinutes(5),
                "epoch-1",
                () -> 123L,
                monotonic::get
        );
        cache.observe(WORKER_ID, Map.of("battery", 87L));

        monotonic.set(Duration.ofMinutes(5).toNanos());
        assertThat(cache.observation(WORKER_ID).freshness()).isEqualTo(
                WorkerObservationSnapshot.PropertiesFreshness.FRESH
        );

        monotonic.incrementAndGet();
        var stale = cache.observation(WORKER_ID);
        assertThat(stale.freshness()).isEqualTo(
                WorkerObservationSnapshot.PropertiesFreshness.STALE
        );
        assertThat(stale.properties()).containsEntry("battery", 87L);
    }

    @Test
    void propertiesAreDefensivelyCapturedAndReturned() {
        AtomicLong monotonic = new AtomicLong();
        WorkerObservationCache cache = new WorkerObservationCache(
                Duration.ofMinutes(5),
                "epoch-1",
                () -> 123L,
                monotonic::get
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
    void unknownWorkerIsEmptyAndClearDropsProjection() {
        WorkerObservationCache cache = new WorkerObservationCache(
                Duration.ofMinutes(5),
                "epoch-1",
                () -> 123L,
                () -> 456L
        );
        cache.observe(WORKER_ID, Map.of());

        assertThat(cache.observation("other-worker")).isNull();
        cache.clear();
        assertThat(cache.observation(WORKER_ID)).isNull();
    }

    @Test
    void staleRouteWriteCanRollbackWithoutRemovingANewerObservation() {
        WorkerObservationCache cache = new WorkerObservationCache(
                Duration.ofMinutes(5),
                "epoch-1",
                () -> 123L,
                () -> 456L
        );
        cache.observe(WORKER_ID, Map.of("battery", 87L));
        WorkerObservationCache.ObservationWrite stale = cache.observe(
                WORKER_ID,
                Map.of("battery", 1L)
        );

        cache.rollback(stale);

        assertThat(cache.observation(WORKER_ID).properties())
                .containsEntry("battery", 87L);

        WorkerObservationCache.ObservationWrite superseded = cache.observe(
                WORKER_ID,
                Map.of("battery", 2L)
        );
        cache.observe(WORKER_ID, Map.of("battery", 3L));
        cache.rollback(superseded);
        assertThat(cache.observation(WORKER_ID).properties())
                .containsEntry("battery", 3L);
    }

    @Test
    void adapterInstancesHaveIndependentEpochsAndRequirePositiveFreshness() {
        WorkerObservationCache first = new WorkerObservationCache(
                Duration.ofMinutes(5)
        );
        WorkerObservationCache second = new WorkerObservationCache(
                Duration.ofMinutes(5)
        );

        assertThat(first.adapterEpoch()).isNotEqualTo(second.adapterEpoch());
        assertThatThrownBy(() -> new WorkerObservationCache(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
