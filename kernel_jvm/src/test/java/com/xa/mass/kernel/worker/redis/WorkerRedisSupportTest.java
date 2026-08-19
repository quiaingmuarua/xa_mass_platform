package com.xa.mass.kernel.worker.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerRedisSupportTest {

    @Test
    void locksCanonicalWorkerPropertiesEnvelope() {
        var envelope = new WorkerRedisSupport.WorkerPropertiesEnvelope(
                123L,
                Map.of("region", "cn-east")
        );
        String encoded = WorkerRedisSupport.encodeWorkerProperties(envelope);

        assertEquals(
                "{\"properties\":{\"region\":\"cn-east\"},"
                        + "\"updatedAtMillis\":123}",
                encoded
        );
        assertEquals(
                envelope,
                WorkerRedisSupport.decodeWorkerProperties(encoded)
        );
        assertNull(WorkerRedisSupport.decodeWorkerProperties(
                "{\"region\":\"legacy\"}"
        ));
    }

    @Test
    void rejectsLegacyWorkerGroupShape() {
        String legacyJson = "{"
                + "\"workerGroupId\":\"legacy-group\","
                + "\"attributes\":{},"
                + "\"eventCodes\":[\"resize\"],"
                + "\"item" + "AllocationFields\":[\"workerId\"]"
                + "}";

        assertNull(WorkerRedisSupport.decodeWorkerGroup(legacyJson));
    }

    @Test
    void rejectsRemovedWorkerGroupIndexDeclaration() {
        String legacyJson = "{"
                + "\"workerGroupId\":\"legacy-group\","
                + "\"attributes\":{},"
                + "\"eventCodes\":[\"resize\"],"
                + "\"indexedPropertyFields\":[\"worker.region\"]"
                + "}";

        assertNull(WorkerRedisSupport.decodeWorkerGroup(legacyJson));
    }

    @Test
    void rejectsLegacyWorkerDescriptorShape() {
        String legacyJson = "{"
                + "\"workerId\":\"worker-1\","
                + "\"workerGroupId\":\"legacy-group\","
                + "\"endpointManagerId\":\"endpoint-manager-1\","
                + "\"attributes\":{},"
                + "\"platform" + "Attributes\":{}"
                + "}";

        assertNull(WorkerRedisSupport.decodeWorkerMetadata(legacyJson));
    }
}
