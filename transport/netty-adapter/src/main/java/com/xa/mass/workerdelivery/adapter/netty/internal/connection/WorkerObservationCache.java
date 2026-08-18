package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import com.xa.mass.workerdelivery.json.Jsons;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** Process-local latest Worker properties projection for one Adapter. */
final class WorkerObservationCache {

    private final ConcurrentMap<String, CachedProperties> propertiesByWorkerId =
            new ConcurrentHashMap<>();
    private final String adapterEpoch;
    private final long freshnessNanos;
    private final LongSupplier wallClockMillis;
    private final LongSupplier monotonicNanos;

    WorkerObservationCache(Duration freshness) {
        this(
                freshness,
                UUID.randomUUID().toString(),
                System::currentTimeMillis,
                System::nanoTime
        );
    }

    WorkerObservationCache(
            Duration freshness,
            String adapterEpoch,
            LongSupplier wallClockMillis,
            LongSupplier monotonicNanos
    ) {
        Objects.requireNonNull(freshness, "freshness");
        if (freshness.isZero()
                || freshness.isNegative()
                || freshness.toMillis() <= 0) {
            throw new IllegalArgumentException(
                    "freshness must be at least one millisecond"
            );
        }
        try {
            freshnessNanos = freshness.toNanos();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(
                    "freshness is too large",
                    error
            );
        }
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
        propertiesByWorkerId.compute(
                requiredWorkerId,
                (ignored, current) -> {
                    previous.set(current);
                    CachedProperties replacement = new CachedProperties(
                            nextRevision(current),
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
            propertiesByWorkerId.remove(
                    requiredWrite.workerId(),
                    requiredWrite.written()
            );
            return;
        }
        propertiesByWorkerId.replace(
                requiredWrite.workerId(),
                requiredWrite.written(),
                requiredWrite.previous()
        );
    }

    PropertiesObservation observation(String workerId) {
        CachedProperties cached = propertiesByWorkerId.get(
                requireWorkerId(workerId)
        );
        if (cached == null) {
            return null;
        }
        long ageNanos = monotonicNanos.getAsLong()
                - cached.observedAtNanos();
        WorkerObservationSnapshot.PropertiesFreshness freshness =
                ageNanos <= freshnessNanos
                        ? WorkerObservationSnapshot.PropertiesFreshness.FRESH
                        : WorkerObservationSnapshot.PropertiesFreshness.STALE;
        return new PropertiesObservation(
                freshness,
                new WorkerObservationSnapshot.PropertiesVersion(
                        adapterEpoch,
                        cached.revision()
                ),
                cached.observedAtMillis(),
                Jsons.parseObject(cached.encodedProperties())
        );
    }

    void clear() {
        propertiesByWorkerId.clear();
    }

    String adapterEpoch() {
        return adapterEpoch;
    }

    private static long nextRevision(CachedProperties current) {
        if (current == null) {
            return 1L;
        }
        return Math.addExact(current.revision(), 1L);
    }

    record PropertiesObservation(
            WorkerObservationSnapshot.PropertiesFreshness freshness,
            WorkerObservationSnapshot.PropertiesVersion version,
            long observedAtMillis,
            Map<String, Object> properties
    ) {

        PropertiesObservation {
            Objects.requireNonNull(freshness, "freshness");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(properties, "properties");
        }
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
