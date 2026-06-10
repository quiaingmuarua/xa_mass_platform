package com.xa.mass.transport.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalWorkerRouteKeyCodecTest {

    @Test
    void encodesWorkerGroupAndWorkerAsStableCanonicalRouteKey() {
        String routeKey = CanonicalWorkerRouteKeyCodec.encode(" phone-device-probe ", " poll-sg-002 ");

        assertEquals("wkr1.cGhvbmUtZGV2aWNlLXByb2Jl.cG9sbC1zZy0wMDI", routeKey);
        CanonicalWorkerRouteKeyCodec.WorkerSubject subject =
                CanonicalWorkerRouteKeyCodec.decode(routeKey);
        assertEquals("phone-device-probe", subject.workerGroupId());
        assertEquals("poll-sg-002", subject.workerId());
        assertTrue(CanonicalWorkerRouteKeyCodec.isCanonical(routeKey));
    }

    @Test
    void doesNotCollapseToRawWorkerIdOrExposeAdapterEvidence() {
        String routeKey = CanonicalWorkerRouteKeyCodec.encode("group-1", "worker-1");

        assertFalse("worker-1".equals(routeKey));
        assertFalse(routeKey.contains("group-1"));
        assertFalse(routeKey.contains("worker-1"));
        assertFalse(routeKey.contains("polling"));
        assertFalse(routeKey.contains("connection"));
    }

    @Test
    void rejectsMissingWorkerSubjectFields() {
        assertThrows(IllegalArgumentException.class,
                () -> CanonicalWorkerRouteKeyCodec.encode(" ", "worker-1"));
        assertThrows(IllegalArgumentException.class,
                () -> CanonicalWorkerRouteKeyCodec.encode("group-1", null));
    }

    @Test
    void rejectsNonCanonicalRouteKeys() {
        assertFalse(CanonicalWorkerRouteKeyCodec.isCanonical("worker-1"));
        assertFalse(CanonicalWorkerRouteKeyCodec.isCanonical("adapter.worker-1"));
        assertThrows(IllegalArgumentException.class,
                () -> CanonicalWorkerRouteKeyCodec.decode("worker-1"));
    }

    @Test
    void acceptsUtf8SubjectsWithoutAddingWhitespaceUnsafeCharacters() {
        String routeKey = CanonicalWorkerRouteKeyCodec.encode("group/cn", "worker:001");

        assertFalse(routeKey.contains(" "));
        assertFalse(routeKey.contains("\n"));
        assertFalse(routeKey.contains("\u0000"));
        CanonicalWorkerRouteKeyCodec.WorkerSubject subject =
                CanonicalWorkerRouteKeyCodec.decode(routeKey);
        assertEquals("group/cn", subject.workerGroupId());
        assertEquals("worker:001", subject.workerId());
    }
}
