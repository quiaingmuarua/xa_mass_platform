package com.xa.mass.kernel.worker.redis;

import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class WorkerRedisSupportTest {

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
