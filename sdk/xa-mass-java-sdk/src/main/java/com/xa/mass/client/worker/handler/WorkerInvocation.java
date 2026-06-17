package com.xa.mass.client.worker.handler;

import com.xa.mass.client.payload.MassPayload;

public record WorkerInvocation(
        String eventCode,
        MassPayload input,
        MassPayload sharedConfig
) {
}
