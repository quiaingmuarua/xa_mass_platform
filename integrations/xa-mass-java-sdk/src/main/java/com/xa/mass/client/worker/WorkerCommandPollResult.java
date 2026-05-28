package com.xa.mass.client.worker;

import java.util.List;

public record WorkerCommandPollResult(
        String workerId,
        List<WorkerCommand> commands,
        int count
) {
}
