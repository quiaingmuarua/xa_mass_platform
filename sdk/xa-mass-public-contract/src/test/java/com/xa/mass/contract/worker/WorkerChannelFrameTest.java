package com.xa.mass.contract.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkerChannelFrameTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void constructsValidFrameAndPreservesOpaqueBody() {
        WorkerChannelFrame frame = new WorkerChannelFrame(
                " frame-1 ",
                " ACTION ",
                " body with spaces "
        );

        assertEquals("frame-1", frame.frameId());
        assertEquals(WorkerChannelFrame.ACTION, frame.kind());
        assertEquals(" body with spaces ", frame.body());
    }

    @Test
    void rejectsMissingRequiredFields() {
        assertThrows(IllegalArgumentException.class, () -> new WorkerChannelFrame(null, WorkerChannelFrame.ACTION, "{}"));
        assertThrows(IllegalArgumentException.class, () -> new WorkerChannelFrame("frame-1", " ", "{}"));
        assertThrows(IllegalArgumentException.class, () -> new WorkerChannelFrame("frame-1", WorkerChannelFrame.ACTION, null));
    }

    @Test
    void serializesStableFieldNamesAndKindConstants() throws Exception {
        WorkerChannelFrame frame = new WorkerChannelFrame(
                "frame-1",
                WorkerChannelFrame.ACTION_REPLY,
                "{\"replyRef\":\"reply-1\"}"
        );

        String json = objectMapper.writeValueAsString(frame);
        WorkerChannelFrame decoded = objectMapper.readValue(json, WorkerChannelFrame.class);

        assertEquals("frame-1", decoded.frameId());
        assertEquals(WorkerChannelFrame.ACTION_REPLY, decoded.kind());
        assertEquals("{\"replyRef\":\"reply-1\"}", decoded.body());
        assertEquals("EVIDENCE_REPORT", WorkerChannelFrame.EVIDENCE_REPORT);
        assertEquals("HEARTBEAT", WorkerChannelFrame.HEARTBEAT);
    }
}
