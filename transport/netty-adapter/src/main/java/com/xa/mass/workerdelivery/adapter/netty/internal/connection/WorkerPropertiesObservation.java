package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import java.util.Map;

/** Repository-internal cached Worker properties projection. */
public record WorkerPropertiesObservation(
        Long updatedAtMillis,
        Map<String, String> properties
) {

    public WorkerPropertiesObservation {
        if ((updatedAtMillis == null) != (properties == null)) {
            throw new IllegalArgumentException(
                    "updatedAtMillis and properties must both be present "
                            + "or both be absent"
            );
        }
        if (properties != null) {
            properties = WorkerDeliveryCodec.copyWorkerProperties(properties);
        }
    }

    static WorkerPropertiesObservation unknown() {
        return new WorkerPropertiesObservation(
                null,
                null
        );
    }

}
