package com.xa.mass.testing.chaos.support;

import java.util.List;

public record CompatibilityMessageSnapshot(List<CompatibilityMessageView> messages,
                                           int limit,
                                           boolean truncated) {
    public CompatibilityMessageSnapshot {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
