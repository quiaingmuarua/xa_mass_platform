package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import java.nio.file.Path;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportQueueEventTest {
    @TempDir Path temporary;

    @Test void defaultOffEventCanBeInstrumentedAndRecordsOnlyFixedQueueEvidence() throws Exception {
        assertThat(ReportQueueEvent.recordingEnabled()).isFalse();
        Path file = temporary.resolve("private.jfr");
        try (var recording = new Recording()) {
            recording.enable(ReportQueueEvent.class);
            recording.start();
            assertThat(ReportQueueEvent.recordingEnabled()).isTrue();
            ReportQueueEvent.record(DeliveryEndpoint.SERVER, "DRAINED", 7, 3);
            recording.stop();
            recording.dump(file);
        }
        var events = RecordingFile.readAllEvents(file).stream()
                .filter(e -> e.getEventType().getName().equals("xa.mass.ReportQueue")).toList();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getString("destination")).isEqualTo("SERVER");
        assertThat(events.getFirst().getString("action")).isEqualTo("DRAINED");
        assertThat(events.getFirst().getInt("depth")).isEqualTo(7);
        assertThat(events.getFirst().getInt("count")).isEqualTo(3);
        assertThat(ReportQueueEvent.recordingEnabled()).isFalse();
    }
}
