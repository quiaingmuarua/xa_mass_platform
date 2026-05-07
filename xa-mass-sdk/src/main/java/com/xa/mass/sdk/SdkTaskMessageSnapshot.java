package com.xa.mass.sdk;

import com.xa.mass.engine.TaskMessageSnapshotView;

import java.util.List;

/**
 * SDK-owned bounded snapshot for compatibility task-message reads.
 */
public record SdkTaskMessageSnapshot(
        List<SdkTaskMessageView> messages,
        int limit,
        boolean truncated
) {

    public SdkTaskMessageSnapshot {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public int returned() {
        return messages.size();
    }

    public static SdkTaskMessageSnapshot from(TaskMessageSnapshotView snapshot) {
        if (snapshot == null) {
            return new SdkTaskMessageSnapshot(List.of(), 0, false);
        }
        return new SdkTaskMessageSnapshot(
                snapshot.messages().stream()
                        .map(SdkTaskMessageView::from)
                        .toList(),
                snapshot.limit(),
                snapshot.truncated()
        );
    }
}
