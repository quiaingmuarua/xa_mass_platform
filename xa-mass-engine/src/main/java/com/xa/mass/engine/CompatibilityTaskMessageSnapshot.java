package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;

import java.util.List;

/**
 * Engine-owned bounded compatibility snapshot for logical task messages.
 */
@CompatibilityProjectionOnly
public record CompatibilityTaskMessageSnapshot(List<CompatibilityTaskMessageView> messages,
                                               int limit,
                                               boolean truncated) {

    public CompatibilityTaskMessageSnapshot {
        messages = messages == null ? List.of() : List.copyOf(messages);
        limit = Math.max(0, limit);
    }

    public int returned() {
        return messages.size();
    }
}
