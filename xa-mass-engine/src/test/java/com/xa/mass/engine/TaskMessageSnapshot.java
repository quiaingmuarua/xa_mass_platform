package com.xa.mass.engine;

import java.util.List;

public record TaskMessageSnapshot(List<TaskMsg> messages, int limit, boolean truncated) {
    public TaskMessageSnapshot {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
