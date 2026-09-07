package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.zip.CRC32C;

/** Process-local latest Worker properties cache for one Adapter. */
final class WorkerPropertiesCache {

    private final Cache<String, CachedProperties> propertiesByWorkerId;
    private final LongSupplier wallClockMillis;

    WorkerPropertiesCache(long maximumEncodedBytes) {
        this(
                maximumEncodedBytes,
                System::currentTimeMillis
        );
    }

    WorkerPropertiesCache(
            long maximumEncodedBytes,
            LongSupplier wallClockMillis
    ) {
        this.wallClockMillis = Objects.requireNonNull(
                wallClockMillis,
                "wallClockMillis"
        );
        propertiesByWorkerId = Caffeine.newBuilder()
                .maximumWeight(maximumEncodedBytes)
                .weigher((String workerId, CachedProperties cached) ->
                        cached.encodedWeight())
                .executor(Runnable::run)
                .build();
    }

    ObservationWrite observe(
            String workerId,
            Map<String, String> properties
    ) {
        String requiredWorkerId = requireWorkerId(workerId);
        Map<String, String> captured = WorkerDeliveryCodec.copyWorkerProperties(
                Objects.requireNonNull(properties, "properties")
        );
        long currentTimeMillis = wallClockMillis.getAsLong();
        AtomicReference<CachedProperties> previous = new AtomicReference<>();
        AtomicReference<CachedProperties> written = new AtomicReference<>();
        propertiesByWorkerId.asMap().compute(
                requiredWorkerId,
                (ignored, current) -> {
                    previous.set(current);
                    CachedProperties replacement = capture(
                            requiredWorkerId,
                            nextUpdatedAtMillis(current, currentTimeMillis),
                            captured
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

    /** A patch without a retained complete baseline is deliberately dropped. */
    ObservationWrite patch(
            String workerId,
            Map<String, String> set,
            List<String> remove
    ) {
        String requiredWorkerId = requireWorkerId(workerId);
        Map<String, String> captured = WorkerDeliveryCodec.copyWorkerProperties(
                Objects.requireNonNull(set, "set")
        );
        List<String> removed = List.copyOf(remove);
        if (new HashSet<>(removed).size() != removed.size()
                || removed.stream().anyMatch(key -> key.isBlank() || captured.containsKey(key))) {
            throw new IllegalArgumentException(
                    "remove must be unique and disjoint from set"
            );
        }
        AtomicReference<ObservationWrite> write = new AtomicReference<>();
        propertiesByWorkerId.asMap().computeIfPresent(
                requiredWorkerId,
                (id, current) -> {
                    Map<String, String> merged = new LinkedHashMap<>(current.properties());
                    removed.forEach(merged::remove);
                    merged.putAll(captured);
                    CachedProperties replacement = capture(
                            id,
                            nextUpdatedAtMillis(current, wallClockMillis.getAsLong()),
                            WorkerDeliveryCodec.copyWorkerProperties(merged)
                    );
                    write.set(new ObservationWrite(id, current, replacement));
                    return replacement;
                }
        );
        return write.get();
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
        return new WorkerPropertiesObservation(
                cached.metadata().updatedAtMillis(),
                cached.properties()
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
                Math.addExact(current.metadata().updatedAtMillis(), 1L)
        );
    }

    private static CachedProperties capture(
            String workerId,
            long updatedAtMillis,
            Map<String, String> properties
    ) {
        byte[] encoded = Jsons.toJson(properties).getBytes(StandardCharsets.UTF_8);
        CRC32C fingerprint = new CRC32C();
        fingerprint.update(encoded, 0, encoded.length);
        long weight = (long) workerId.getBytes(StandardCharsets.UTF_8).length
                + encoded.length;
        return new CachedProperties(
                properties,
                new Metadata(fingerprint.getValue(), updatedAtMillis),
                (int) Math.min(weight, Integer.MAX_VALUE)
        );
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

    record CachedProperties(
            Map<String, String> properties,
            Metadata metadata,
            int encodedWeight
    ) {
    }

    record Metadata(long propertiesFingerprint, long updatedAtMillis) {
    }

    private static String requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must be non-blank");
        }
        return workerId;
    }
}
