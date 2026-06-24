package com.xa.mass.transport.runtime.embedded;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdapterSessionIdentityTest {

    @Test
    void trimsDeliveryBucketAndWorkerIds() {
        AdapterSessionIdentity identity = new AdapterSessionIdentity(" bucket-1 ", " worker-1 ");

        assertEquals("bucket-1", identity.deliveryBucketId());
        assertEquals("worker-1", identity.workerId());
    }

    @Test
    void rejectsBlankFields() {
        assertThrows(IllegalArgumentException.class, () -> new AdapterSessionIdentity(" ", "worker-1"));
        assertThrows(IllegalArgumentException.class, () -> new AdapterSessionIdentity("bucket-1", ""));
    }
}
