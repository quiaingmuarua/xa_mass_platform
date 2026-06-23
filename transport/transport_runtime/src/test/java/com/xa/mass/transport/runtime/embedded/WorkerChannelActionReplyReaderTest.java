package com.xa.mass.transport.runtime.embedded;

import com.xa.mass.contract.worker.WorkerChannelFrame;
import com.xa.mass.contract.worker.WorkerChannelFrameJsonCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerChannelActionReplyReaderTest {

    private final WorkerChannelFrameJsonCodec frameCodec = new WorkerChannelFrameJsonCodec();
    private final WorkerChannelActionReplyReader reader = new WorkerChannelActionReplyReader();

    @Test
    void readsActionReplyFrameFactsWithoutChangingBody() {
        String body = " {\"replyRef\":\" reply-1 \",\"success\":true,\"body\":\" done \"} ";
        String frameJson = frameCodec.encode(new WorkerChannelFrame(
                "frame-1",
                WorkerChannelFrame.ACTION_REPLY,
                body
        ));

        WorkerChannelActionReplyFrame reply = reader.read(frameJson);

        assertEquals("frame-1", reply.frameId());
        assertEquals("reply-1", reply.replyRef());
        assertEquals(body, reply.body());
        assertEquals("reply-1", reader.replyRef(frameJson));
        assertTrue(reader.isActionReplyFrame(frameJson));
    }

    @Test
    void ignoresNonReplyFramesForRecognition() {
        String frameJson = frameCodec.encode(new WorkerChannelFrame(
                "frame-1",
                WorkerChannelFrame.ACTION,
                "{}"
        ));

        assertFalse(reader.isActionReplyFrame(frameJson));
        assertThrows(IllegalArgumentException.class, () -> reader.read(frameJson));
    }

    @Test
    void rejectsReplyWithoutReplyRef() {
        String frameJson = frameCodec.encode(new WorkerChannelFrame(
                "frame-1",
                WorkerChannelFrame.ACTION_REPLY,
                "{\"success\":true,\"body\":\"ok\"}"
        ));

        assertTrue(reader.isActionReplyFrame(frameJson));
        assertThrows(IllegalArgumentException.class, () -> reader.read(frameJson));
    }
}
