package com.xa.mass.transport.runtime.embedded;

import com.google.gson.JsonObject;
import com.xa.mass.contract.worker.WorkerChannelFrame;
import com.xa.mass.contract.worker.WorkerChannelFrameJsonCodec;
import com.xa.mass.transport.runtime.frame.TransportJsonFrameParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerChannelActionReplyResultFrameReaderTest {

    private final TransportJsonFrameParser parser = new TransportJsonFrameParser();
    private final WorkerChannelFrameJsonCodec frameCodec = new WorkerChannelFrameJsonCodec();
    private final WorkerChannelActionReplyResultFrameReader reader =
            new WorkerChannelActionReplyResultFrameReader(parser);

    @Test
    void readsActionReplyFrameAsResultFactsWithoutChangingBody() {
        String body = " {\"replyRef\":\" reply-1 \",\"success\":true,\"body\":\" done \"} ";
        JsonObject frame = parser.parseObject(frameCodec.encode(new WorkerChannelFrame(
                "frame-1",
                WorkerChannelFrame.ACTION_REPLY,
                body
        )));

        assertTrue(reader.isResultFrame(frame));
        AdapterResultFrame result = reader.read(frame);

        assertEquals("reply-1", result.correlationRef());
        assertEquals(body, result.payload());
        assertEquals("frame-1", result.traceSeed());
        assertEquals("frame-1", result.frameId());
    }

    @Test
    void ignoresNonReplyFramesForRecognition() {
        JsonObject frame = parser.parseObject(frameCodec.encodeGenerated(
                WorkerChannelFrame.ACTION,
                "{}"
        ));

        assertFalse(reader.isResultFrame(frame));
        assertThrows(IllegalArgumentException.class, () -> reader.read(frame));
    }

    @Test
    void rejectsLegacyResultCorrelationAlias() {
        JsonObject body = new JsonObject();
        body.addProperty("resultCorrelationRef", "legacy-corr");
        body.addProperty("success", true);
        body.addProperty("body", "ok");
        JsonObject frame = parser.parseObject(frameCodec.encodeGenerated(
                WorkerChannelFrame.ACTION_REPLY,
                parser.toJson(body)
        ));

        assertTrue(reader.isResultFrame(frame));
        assertThrows(IllegalArgumentException.class, () -> reader.read(frame));
    }
}
