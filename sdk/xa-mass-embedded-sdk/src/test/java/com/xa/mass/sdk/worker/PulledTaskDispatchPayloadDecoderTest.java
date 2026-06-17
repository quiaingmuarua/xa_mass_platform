package com.xa.mass.sdk.worker;

import com.xa.mass.transport.channel.PulledDeliveryMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PulledTaskDispatchPayloadDecoderTest {

    private final PulledTaskDispatchPayloadDecoder decoder = new PulledTaskDispatchPayloadDecoder();

    @Test
    void decodesOpaquePulledDeliveryMessageIntoSdkTaskDispatch() {
        PulledTaskDispatch decoded = decoder.decode(new PulledDeliveryMessage(
                "delivery-1",
                "worker-1",
                """
                {
                  "resultCorrelationRef": "corr-1",
                  "eventCode": "crawler.fetch-page",
                  "input": {"target": "target-1"},
                  "sharedConfig": {"mode": "fast"}
                }
                """,
                "corr-1",
                10L
        ));

        assertEquals("corr-1", decoded.getResultCorrelationRef());
        assertEquals("crawler.fetch-page", decoded.getEventCode());
        assertEquals("target-1", decoded.getInput().get("target"));
        assertEquals("fast", decoded.getSharedConfig().get("mode"));
    }
}
