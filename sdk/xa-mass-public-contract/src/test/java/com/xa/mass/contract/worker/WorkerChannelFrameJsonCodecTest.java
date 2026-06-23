package com.xa.mass.contract.worker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkerChannelFrameJsonCodecTest {

    private final WorkerChannelFrameJsonCodec codec = new WorkerChannelFrameJsonCodec();

    @Test
    void encodesAndDecodesWorkerChannelFrameWithoutParsingBody() {
        String body = " {\"replyRef\":\"reply-1\"} ";

        String json = codec.encode(new WorkerChannelFrame(
                "frame-1",
                WorkerChannelFrame.ACTION_REPLY,
                body
        ));
        WorkerChannelFrame decoded = codec.decode(json);

        assertEquals("frame-1", decoded.frameId());
        assertEquals(WorkerChannelFrame.ACTION_REPLY, decoded.kind());
        assertEquals(body, decoded.body());
    }

    @Test
    void generatedActionFrameUsesSharedKindAndOpaqueBody() {
        String json = codec.encodeAction(" payload ");

        WorkerChannelFrame decoded = codec.decode(json);

        assertEquals(WorkerChannelFrame.ACTION, decoded.kind());
        assertEquals(" payload ", decoded.body());
    }

    @Test
    void ignoresProtocolLocalOuterFrameDiagnostics() {
        WorkerChannelFrame decoded = codec.decode("""
                {
                  "frameId": "frame-1",
                  "kind": "ACTION_REPLY",
                  "body": "{}",
                  "routeKey": "route-1",
                  "traceId": "trace-1"
                }
                """);

        assertEquals("frame-1", decoded.frameId());
        assertEquals(WorkerChannelFrame.ACTION_REPLY, decoded.kind());
        assertEquals("{}", decoded.body());
    }

    @Test
    void rejectsInvalidJsonAndMissingBody() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{"));
        assertThrows(IllegalArgumentException.class, () -> codec.encodeGenerated(WorkerChannelFrame.ACTION, null));
    }
}
