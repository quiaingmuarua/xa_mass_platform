package com.xa.mass.client.worker;

import java.util.List;

public record WorkerPollResult(
        String workerId,
        List<WorkerInvocation> items,
        int total
) {
}
