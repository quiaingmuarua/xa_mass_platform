package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Repository-internal projection returned by Adapter observation events. */
public record WorkerObservationSnapshot(
        WorkerConnectionState connectionState,
        PropertiesFreshness propertiesFreshness,
        PropertiesVersion propertiesVersion,
        Long propertiesObservedAtMillis,
        Map<String, Object> properties
) {

    public WorkerObservationSnapshot {
        Objects.requireNonNull(connectionState, "connectionState");
        Objects.requireNonNull(propertiesFreshness, "propertiesFreshness");
        if (propertiesFreshness == PropertiesFreshness.UNKNOWN) {
            if (propertiesVersion != null
                    || propertiesObservedAtMillis != null
                    || properties != null) {
                throw new IllegalArgumentException(
                        "Unknown properties must not carry observation data"
                );
            }
        } else {
            Objects.requireNonNull(propertiesVersion, "propertiesVersion");
            Objects.requireNonNull(
                    propertiesObservedAtMillis,
                    "propertiesObservedAtMillis"
            );
            properties = freezeObject(
                    Objects.requireNonNull(properties, "properties")
            );
        }
    }

    public enum PropertiesFreshness {
        FRESH,
        STALE,
        UNKNOWN
    }

    public record PropertiesVersion(String adapterEpoch, long revision) {

        public PropertiesVersion {
            if (adapterEpoch == null || adapterEpoch.isBlank()) {
                throw new IllegalArgumentException(
                        "adapterEpoch must be non-blank"
                );
            }
            if (revision <= 0) {
                throw new IllegalArgumentException(
                        "revision must be positive"
                );
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> freezeObject(
            Map<String, Object> value
    ) {
        return (Map<String, Object>) freeze(value);
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copied = new LinkedHashMap<>();
            map.forEach((key, item) -> copied.put(
                    Objects.requireNonNull((String) key, "property key"),
                    freeze(item)
            ));
            return Collections.unmodifiableMap(copied);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copied = new ArrayList<>(collection.size());
            collection.forEach(item -> copied.add(freeze(item)));
            return Collections.unmodifiableList(copied);
        }
        return value;
    }
}
