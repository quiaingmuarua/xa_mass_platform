package com.xa.mass.kernel.worker.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class WorkerRedisSupportTest {

    @Test
    void locksExplicitIndexRedisAbi() {
        assertEquals(
                "wr:test:property-index:image-workers:"
                        + "index.worker.region:values",
                WorkerRedisSupport.propertyValuesKey(
                        "test",
                        "image-workers",
                        "index.worker.region"
                )
        );
        assertEquals(
                "{\"value\":\"cn-east\"}",
                WorkerRedisSupport.encodeIndexedPropertyValue("cn-east")
        );
        assertEquals(
                "cn-east",
                WorkerRedisSupport.decodeIndexedPropertyValue(
                        "{\"value\":\"cn-east\"}"
                )
        );
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

        assertNull(WorkerRedisSupport.decodeWorker(legacyJson));
    }
}
