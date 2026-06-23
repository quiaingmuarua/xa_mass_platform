package com.xa.mass.client.worker.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.client.worker.WorkerAction;
import com.xa.mass.client.worker.WorkerChannelFrame;
import com.xa.mass.client.worker.handler.WorkerActionResult;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WebSocketWorkerProtocolDriverTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final WebSocketWorkerProtocolDriver driver = new WebSocketWorkerProtocolDriver(
            "worker-1",
            "group-1",
            URI.create("ws://localhost:8080/ws"),
            Duration.ofSeconds(5),
            HttpClient.newHttpClient(),
            OBJECT_MAPPER,
            (endpoint, listener) -> {
                throw new UnsupportedOperationException("connect is not used by protocol codec tests");
            }
    );

    @Test
    void decodesActionChannelFrameBody() throws Exception {
        String actionBody = OBJECT_MAPPER.writeValueAsString(Map.of(
                "actionId", "action-1",
                "replyRef", "reply-1",
                "eventCode", "probe.phone.metadata",
                "body", " raw body ",
                "sharedConfig", Map.of("routingCode", "sg")
        ));
        String frame = OBJECT_MAPPER.writeValueAsString(new WorkerChannelFrame(
                "frame-1",
                WorkerChannelFrame.ACTION,
                actionBody
        ));

        WorkerAction action = driver.decodeDispatchFrame(frame);

        assertEquals("action-1", action.actionId());
        assertEquals("reply-1", action.replyRef());
        assertEquals("probe.phone.metadata", action.eventCode());
        assertEquals(" raw body ", action.body());
        assertEquals("sg", action.sharedConfigValues().get("routingCode"));
    }

    @Test
    void ignoresNonActionChannelFrames() throws Exception {
        String frame = OBJECT_MAPPER.writeValueAsString(new WorkerChannelFrame(
                "frame-1",
                WorkerChannelFrame.HEARTBEAT,
                "{}"
        ));

        assertNull(driver.decodeDispatchFrame(frame));
    }

    @Test
    void encodesActionReplyChannelFrame() throws Exception {
        String encoded = driver.encodeResultFrame(
                "reply-1",
                WorkerActionResult.failure("NO_ENDPOINT", "worker not reachable")
        );

        JsonNode frame = OBJECT_MAPPER.readTree(encoded);
        assertEquals(WorkerChannelFrame.ACTION_REPLY, frame.get("kind").asText());
        JsonNode body = OBJECT_MAPPER.readTree(frame.get("body").asText());
        assertEquals("reply-1", body.get("replyRef").asText());
        assertEquals(false, body.get("success").asBoolean());
        assertEquals("NO_ENDPOINT", body.get("code").asText());
        assertEquals("worker not reachable", body.get("body").asText());
    }
}
