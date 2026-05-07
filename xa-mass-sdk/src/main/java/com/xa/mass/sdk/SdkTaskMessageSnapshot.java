package com.xa.mass.sdk;

import java.util.List;

/**
 * Bounded SDK-owned compatibility snapshot for task messages.
 */
public record SdkTaskMessageSnapshot(List<SdkTaskMessageView> messages,
                                     int limit,
                                     boolean truncated) {

    public SdkTaskMessageSnapshot {
        messages = messages == null ? List.of() : List.copyOf(messages);
        limit = Math.max(0, limit);
    }

    public int returned() {
        return messages.size();
    }
}
