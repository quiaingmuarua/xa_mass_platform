package com.xa.mass.sdk.worker;

import com.xa.mass.transport.channel.PulledDeliveryMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkerActionPayloadDecoderTest {

    private final WorkerActionPayloadDecoder decoder = new WorkerActionPayloadDecoder();

    @Test
    void decodesOpaquePulledDeliveryMessageIntoSdkTaskDispatch() {
        WorkerAction decoded = decoder.decode(new PulledDeliveryMessage(
                "delivery-1",
                "worker-1",
                """
                {
                  "actionId": "action-1",
                  "replyRef": "corr-1",
                  "eventCode": "crawler.fetch-page",
                  "body": "{\\"target\\":\\"target-1\\"}",
                  "sharedConfig": {"mode": "fast"}
                }
                """,
                "corr-1",
                10L
        ));

        assertEquals("action-1", decoded.getActionId());
        assertEquals("corr-1", decoded.getReplyRef());
        assertEquals("crawler.fetch-page", decoded.getEventCode());
        assertEquals("{\"target\":\"target-1\"}", decoded.getBody());
        assertEquals("fast", decoded.getSharedConfig().get("mode"));
    }
}
