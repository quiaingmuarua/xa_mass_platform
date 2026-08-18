package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.xa.mass.workerdelivery.adapter.netty.NettyWorkerObservationCacheConfig;
import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** Process-local latest Worker properties cache for one Adapter. */
final class WorkerPropertiesCache {

    private final Cache<String, CachedProperties> propertiesByWorkerId;
    private final String adapterEpoch;
    private final long freshnessNanos;
    private final LongSupplier wallClockMillis;
    private final LongSupplier monotonicNanos;
    private final AtomicLong observationRevision = new AtomicLong();

    WorkerPropertiesCache(NettyWorkerObservationCacheConfig config) {
        this(
                config,
                UUID.randomUUID().toString(),
                System::currentTimeMillis,
                System::nanoTime
        );
    }

    WorkerPropertiesCache(
            NettyWorkerObservationCacheConfig config,
            String adapterEpoch,
            LongSupplier wallClockMillis,
            LongSupplier monotonicNanos
    ) {
        NettyWorkerObservationCacheConfig requiredConfig =
                Objects.requireNonNull(config, "config");
        freshnessNanos = requiredConfig.freshness().toNanos();
        if (adapterEpoch == null || adapterEpoch.isBlank()) {
            throw new IllegalArgumentException(
                    "adapterEpoch must be non-blank"
            );
        }
        this.adapterEpoch = adapterEpoch;
        this.wallClockMillis = Objects.requireNonNull(
                wallClockMillis,
                "wallClockMillis"
        );
        this.monotonicNanos = Objects.requireNonNull(
                monotonicNanos,
                "monotonicNanos"
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
        long observedAtMillis = wallClockMillis.getAsLong();
        long observedAtNanos = monotonicNanos.getAsLong();
        AtomicReference<CachedProperties> previous = new AtomicReference<>();
        AtomicReference<CachedProperties> written = new AtomicReference<>();
        propertiesByWorkerId.asMap().compute(
                requiredWorkerId,
                (ignored, current) -> {
                    previous.set(current);
                    CachedProperties replacement = new CachedProperties(
                            nextRevision(),
                            observedAtMillis,
                            observedAtNanos,
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

    WorkerPropertiesObservation observation(String workerId) {
        CachedProperties cached = propertiesByWorkerId.policy()
                .getIfPresentQuietly(requireWorkerId(workerId));
        if (cached == null) {
            return WorkerPropertiesObservation.unknown();
        }
        long ageNanos = monotonicNanos.getAsLong()
                - cached.observedAtNanos();
        WorkerPropertiesObservation.Freshness freshness =
                ageNanos <= freshnessNanos
                        ? WorkerPropertiesObservation.Freshness.FRESH
                        : WorkerPropertiesObservation.Freshness.STALE;
        return new WorkerPropertiesObservation(
                freshness,
                new WorkerPropertiesObservation.Version(
                        adapterEpoch,
                        cached.revision()
                ),
                cached.observedAtMillis(),
                Jsons.parseObject(cached.encodedProperties())
        );
    }

    void clear() {
        propertiesByWorkerId.invalidateAll();
        propertiesByWorkerId.cleanUp();
    }

    String adapterEpoch() {
        return adapterEpoch;
    }

    private long nextRevision() {
        while (true) {
            long current = observationRevision.get();
            long next = Math.addExact(current, 1L);
            if (observationRevision.compareAndSet(current, next)) {
                return next;
            }
        }
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
            long revision,
            long observedAtMillis,
            long observedAtNanos,
            String encodedProperties
    ) {

        private CachedProperties {
            if (revision <= 0) {
                throw new IllegalArgumentException(
                        "revision must be positive"
                );
            }
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
