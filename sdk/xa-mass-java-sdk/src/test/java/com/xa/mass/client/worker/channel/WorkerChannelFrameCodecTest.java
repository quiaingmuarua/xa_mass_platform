package com.xa.mass.client.worker.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.client.worker.WorkerAction;
import com.xa.mass.client.worker.handler.WorkerActionResult;
import com.xa.mass.contract.worker.WorkerChannelFrame;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorkerChannelFrameCodecTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final WorkerChannelFrameCodec codec = new WorkerChannelFrameCodec(objectMapper);

    @Test
    void decodesOnlyActionFramesIntoWorkerActions() throws Exception {
        String actionBody = objectMapper.writeValueAsString(Map.of(
                "actionId", "action-1",
                "replyRef", "reply-1",
                "eventCode", "probe.phone.metadata",
                "body", "raw-body",
                "sharedConfig", Map.of("routingCode", "sg")
        ));
        String frame = objectMapper.writeValueAsString(new WorkerChannelFrame(
                "frame-1",
                WorkerChannelFrame.ACTION,
                actionBody
        ));

        WorkerAction action = codec.decodeActionFrame(frame);

        assertEquals("action-1", action.actionId());
        assertEquals("reply-1", action.replyRef());
        assertEquals("probe.phone.metadata", action.eventCode());
        assertEquals("raw-body", action.body());
        assertEquals("sg", action.sharedConfigValues().get("routingCode"));

        String heartbeat = objectMapper.writeValueAsString(new WorkerChannelFrame(
                "frame-2",
                WorkerChannelFrame.HEARTBEAT,
                "{}"
        ));
        assertNull(codec.decodeActionFrame(heartbeat));
    }

    @Test
    void encodesActionReplyFrames() throws Exception {
        String encoded = codec.encodeActionReplyFrame(
                "reply-1",
                WorkerActionResult.success("done")
        );

        JsonNode frame = objectMapper.readTree(encoded);
        assertEquals(WorkerChannelFrame.ACTION_REPLY, frame.get("kind").asText());
        JsonNode reply = objectMapper.readTree(frame.get("body").asText());
        assertEquals("reply-1", reply.get("replyRef").asText());
        assertEquals(true, reply.get("success").asBoolean());
        assertEquals(true, reply.get("code").isNull());
        assertEquals("done", reply.get("body").asText());
    }
}
