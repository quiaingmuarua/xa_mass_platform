package com.xa.mass.base.model;

import java.util.List;

/**
 * Bounded compatibility snapshot of task-message projections.
 *
 * <p>This is a shell/debug read model only. It must not be treated as
 * runtime queue truth or as a license to scan full per-task message state.
 */
public record TaskMessageSnapshot(List<TaskMsg> messages, int limit, boolean truncated) {

    public TaskMessageSnapshot {
        messages = messages == null ? List.of() : List.copyOf(messages);
        limit = Math.max(0, limit);
    }

    public int returned() {
        return messages.size();
    }
}
