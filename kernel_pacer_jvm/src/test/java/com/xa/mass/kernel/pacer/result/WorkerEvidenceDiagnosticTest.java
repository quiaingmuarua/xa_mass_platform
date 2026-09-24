package com.xa.mass.kernel.pacer.result;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.xa.mass.kernel.worker.WorkerServiceabilityEvents;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.nio.file.Path;
import java.util.List;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class WorkerEvidenceDiagnosticTest {
    @TempDir Path temporary;

    @Test void disabledObservationIsInert() {
        assertEquals(0, WorkerEvidenceDiagnostic.batch());
        assertDoesNotThrow(() -> WorkerEvidenceDiagnostic.record(0, null, null, null, null, 0, 0));
    }

    @Test void recordsAgeRejectionAndBatchSelectionWithoutChangingTheEvent() throws Exception {
        var owner = mock(WorkerServiceabilityEvents.class);
        var policy = new WorkerServiceabilityResultPolicy(owner, WorkerServiceabilityResultConfig.defaults(),
                () -> 50_000L, JsonMapper.builder().build());
        var path = temporary.resolve("evidence.jfr");
        try (var recording = new Recording()) {
            recording.enable(WorkerEvidenceDiagnostic.RecordedEvent.class);
            recording.start();
            assertTrue(WorkerEvidenceDiagnostic.batch() > 0, "diagnostic recording must be enabled");
            policy.handle(List.of(connected(19_000L), connected(49_000L)));
            recording.stop();
            recording.dump(path);
        }
        verify(owner).onAvailable(java.util.Map.of("worker-1",
                new WorkerServiceabilityEvents.NetworkObservation("adapter-1", 49_000L)));
        verifyNoMoreInteractions(owner);
        var events = RecordingFile.readAllEvents(path);
        assertEquals(List.of("DROPPED_AGE", "DECODED", "SELECTED"),
                events.stream().map(e -> e.getString("stage")).toList());
        assertEquals(1, events.stream().map(e -> e.getLong("batchId")).distinct().count());
        for (var event : events) {
            assertEquals(64, event.getString("workerKey").length());
            assertEquals("AVAILABLE", event.getString("kind"));
            assertFalse(event.hasField("workerId"));
            assertFalse(event.hasField("payload"));
        }
    }

    private static DeliveryReport connected(long time) {
        return DeliveryReport.create(DeliveryEndpoint.ADAPTER, "adapter-1", DeliveryEndpoint.KERNEL,
                "platform.adapter.worker-connection.changed", "",
                "{\"workerId\":\"worker-1\",\"state\":\"CONNECTED\",\"observedAtMillis\":" + time + "}",
                "worker-serviceability-evidence:v1");
    }
}
