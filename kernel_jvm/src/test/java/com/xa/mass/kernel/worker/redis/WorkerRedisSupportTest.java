package com.xa.mass.kernel.worker.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerDescriptor;
import org.junit.jupiter.api.Test;

class WorkerRedisSupportTest {

    @Test
    void bindingKeepsCanonicalEscapingAndStrictFieldTypes() {
        String encoded = WorkerRedisSupport.encodeBinding("群", "地址");
        assertEquals("{\"endpointManagerId\":\"\\u5730\\u5740\",\"workerGroupId\":\"\\u7fa4\"}", encoded);
        assertEquals(new WorkerDescriptor("w", "群", "地址"), WorkerRedisSupport.decodeBinding("w", encoded));
        assertNull(WorkerRedisSupport.decodeBinding("w", "{\"workerGroupId\":\"g\",\"endpointManagerId\":5}"));
        assertNull(WorkerRedisSupport.decodeBinding("w", "{\"workerGroupId\":\"g\",\"endpointManagerId\":\"\"}"));
    }

    @Test
    void bindingStoresOnlyGroupAndEndpoint() {
        var descriptor = new WorkerDescriptor("worker-1", "group-1", "endpoint-1");
        String stored = "{\"endpointManagerId\":\"endpoint-1\","
                + "\"workerGroupId\":\"group-1\"}";

        assertEquals(stored, WorkerRedisSupport.encodeBinding("group-1", "endpoint-1"));
        assertEquals(descriptor, WorkerRedisSupport.decodeBinding("worker-1", stored));
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

        assertNull(WorkerRedisSupport.decodeBinding("worker-1", legacyJson));
    }
}
