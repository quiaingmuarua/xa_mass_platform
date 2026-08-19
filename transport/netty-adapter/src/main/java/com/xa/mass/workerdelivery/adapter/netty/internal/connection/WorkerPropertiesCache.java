package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.xa.mass.workerdelivery.adapter.netty.NettyWorkerPropertiesCacheConfig;
import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** Process-local latest Worker properties cache for one Adapter. */
final class WorkerPropertiesCache {

    private final Cache<String, CachedProperties> propertiesByWorkerId;
    private final LongSupplier wallClockMillis;

    WorkerPropertiesCache(NettyWorkerPropertiesCacheConfig config) {
        this(
                config,
                System::currentTimeMillis
        );
    }

    WorkerPropertiesCache(
            NettyWorkerPropertiesCacheConfig config,
            LongSupplier wallClockMillis
    ) {
        NettyWorkerPropertiesCacheConfig requiredConfig =
                Objects.requireNonNull(config, "config");
        this.wallClockMillis = Objects.requireNonNull(
                wallClockMillis,
                "wallClockMillis"
        );
        propertiesByWorkerId = Caffeine.newBuilder()
                .maximumWeight(requiredConfig.maximumEncodedBytes())
                .weigher((String workerId, CachedProperties cached) ->
                        encodedWeight(workerId, cached.encodedProperties()))
                .executor(Runnable::run)
                .build();
    }

    ObservationWrite observe(
            String workerId,
            Map<String, Object> properties
    ) {
        String requiredWorkerId = requireWorkerId(workerId);
        String encodedProperties = Jsons.toJson(
                Objects.requireNonNull(properties, "properties")
        );
        long currentTimeMillis = wallClockMillis.getAsLong();
        AtomicReference<CachedProperties> previous = new AtomicReference<>();
        AtomicReference<CachedProperties> written = new AtomicReference<>();
        propertiesByWorkerId.asMap().compute(
                requiredWorkerId,
                (ignored, current) -> {
                    previous.set(current);
                    CachedProperties replacement = new CachedProperties(
                            nextUpdatedAtMillis(current, currentTimeMillis),
                            encodedProperties
                    );
                    written.set(replacement);
                    return replacement;
                }
        );
        return new ObservationWrite(
                requiredWorkerId,
                previous.get(),
                written.get()
        );
    }

    void rollback(ObservationWrite write) {
        ObservationWrite requiredWrite = Objects.requireNonNull(
                write,
                "write"
        );
        if (requiredWrite.previous() == null) {
            propertiesByWorkerId.asMap().remove(
                    requiredWrite.workerId(),
                    requiredWrite.written()
            );
            return;
        }
        propertiesByWorkerId.asMap().replace(
                requiredWrite.workerId(),
                requiredWrite.written(),
                requiredWrite.previous()
        );
    }

    WorkerPropertiesObservation committedObservation(
            ObservationWrite write
    ) {
        ObservationWrite requiredWrite = Objects.requireNonNull(
                write,
                "write"
        );
        CachedProperties current = propertiesByWorkerId.policy()
                .getIfPresentQuietly(requiredWrite.workerId());
        if (current != requiredWrite.written()) {
            return null;
        }
        return new WorkerPropertiesObservation(
                current.updatedAtMillis(),
                Jsons.parseObject(current.encodedProperties())
        );
    }

    WorkerPropertiesObservation observation(String workerId) {
        CachedProperties cached = propertiesByWorkerId.policy()
                .getIfPresentQuietly(requireWorkerId(workerId));
        if (cached == null) {
            return WorkerPropertiesObservation.unknown();
        }
        return new WorkerPropertiesObservation(
                cached.updatedAtMillis(),
                Jsons.parseObject(cached.encodedProperties())
        );
    }

    void invalidate(String workerId) {
        propertiesByWorkerId.invalidate(requireWorkerId(workerId));
    }

    void clear() {
        propertiesByWorkerId.invalidateAll();
        propertiesByWorkerId.cleanUp();
    }

    private static long nextUpdatedAtMillis(
            CachedProperties current,
            long currentTimeMillis
    ) {
        if (current == null) {
            return currentTimeMillis;
        }
        return Math.max(
                currentTimeMillis,
                Math.addExact(current.updatedAtMillis(), 1L)
        );
    }

    private static int encodedWeight(
            String workerId,
            String encodedProperties
    ) {
        long weight = (long) workerId.getBytes(StandardCharsets.UTF_8).length
                + encodedProperties.getBytes(StandardCharsets.UTF_8).length;
        return weight >= Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : (int) weight;
    }

    record ObservationWrite(
            String workerId,
            CachedProperties previous,
            CachedProperties written
    ) {

        ObservationWrite {
            Objects.requireNonNull(workerId, "workerId");
            Objects.requireNonNull(written, "written");
        }
    }

    private record CachedProperties(
            long updatedAtMillis,
            String encodedProperties
    ) {

        private CachedProperties {
            Objects.requireNonNull(encodedProperties, "encodedProperties");
        }
    }

    private static String requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must be non-blank");
        }
        return workerId;
    }
}
