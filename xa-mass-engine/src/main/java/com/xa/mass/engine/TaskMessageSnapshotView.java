package com.xa.mass.engine;

import java.util.List;

/**
 * Engine-owned bounded compatibility snapshot of task-message read views.
 */
public record TaskMessageSnapshotView(
        List<TaskMessageView> messages,
        int limit,
        boolean truncated
) {

    public TaskMessageSnapshotView {
        messages = messages == null ? List.of() : List.copyOf(messages);
        limit = Math.max(0, limit);
    }

    public int returned() {
        return messages.size();
    }
}
