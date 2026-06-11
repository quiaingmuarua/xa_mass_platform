package com.xa.mass.transport.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalWorkerGroupRouteKeyCodecTest {

    @Test
    void encodesAndDecodesWorkerGroupSubject() {
        String routeKey = CanonicalWorkerGroupRouteKeyCodec.encode(" phone-device-probe ");

        assertEquals("wkg1.cGhvbmUtZGV2aWNlLXByb2Jl", routeKey);
        CanonicalWorkerGroupRouteKeyCodec.WorkerGroupSubject subject =
                CanonicalWorkerGroupRouteKeyCodec.decode(routeKey);
        assertEquals("phone-device-probe", subject.workerGroupId());
        assertTrue(CanonicalWorkerGroupRouteKeyCodec.isCanonical(routeKey));
    }

    @Test
    void routeKeyDoesNotExposeWorkerOrAdapterIdentity() {
        String routeKey = CanonicalWorkerGroupRouteKeyCodec.encode("group-1");

        assertFalse("worker-1".equals(routeKey));
        assertFalse(routeKey.contains("group-1"));
        assertFalse(routeKey.contains("worker-1"));
        assertFalse(routeKey.contains("polling"));
        assertFalse(routeKey.contains("connection"));
    }

    @Test
    void rejectsBlankSubjects() {
        IllegalArgumentException groupError = assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalWorkerGroupRouteKeyCodec.encode(" "));
        assertEquals("workerGroupId must not be blank", groupError.getMessage());
    }

    @Test
    void rejectsNonCanonicalRouteKeys() {
        assertFalse(CanonicalWorkerGroupRouteKeyCodec.isCanonical("worker-1"));
        assertFalse(CanonicalWorkerGroupRouteKeyCodec.isCanonical("adapter.worker-1"));
        assertThrows(IllegalArgumentException.class,
                () -> CanonicalWorkerGroupRouteKeyCodec.decode("worker-1"));
    }

    @Test
    void urlSafeEncodingHandlesSpecialCharacters() {
        String routeKey = CanonicalWorkerGroupRouteKeyCodec.encode("group/cn");

        assertFalse(routeKey.contains(" "));
        assertFalse(routeKey.contains("\n"));
        assertFalse(routeKey.contains("\u0000"));

        CanonicalWorkerGroupRouteKeyCodec.WorkerGroupSubject subject =
                CanonicalWorkerGroupRouteKeyCodec.decode(routeKey);
        assertEquals("group/cn", subject.workerGroupId());
    }
}
