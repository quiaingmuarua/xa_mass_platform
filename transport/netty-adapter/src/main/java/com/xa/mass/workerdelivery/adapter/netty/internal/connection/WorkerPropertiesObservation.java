package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Repository-internal cached Worker properties projection. */
public record WorkerPropertiesObservation(
        Long updatedAtMillis,
        Map<String, Object> properties
) {

    public WorkerPropertiesObservation {
        if ((updatedAtMillis == null) != (properties == null)) {
            throw new IllegalArgumentException(
                    "updatedAtMillis and properties must both be present "
                            + "or both be absent"
            );
        }
        if (properties != null) {
            properties = freezeObject(
                    properties
            );
        }
    }

    static WorkerPropertiesObservation unknown() {
        return new WorkerPropertiesObservation(
                null,
                null
        );
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
