package com.xa.mass.workerdelivery.adapter.message;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;

final class TestMessages {

    private TestMessages() {
    }

    static SeedResult successResult(String commandId) {
        return new SeedResult(
                commandId,
                "context",
                "200",
                "null"
        );
    }
}
