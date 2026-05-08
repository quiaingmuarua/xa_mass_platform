package com.xa.mass.transport.channel;

import com.xa.mass.transport.model.TaskDispatchItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskPullResultTest {

    @Test
    void deliveredRequiresAtLeastOneItem() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TaskPullResult.delivered(List.of())
        );

        assertEquals("delivered pull result must include at least one item", error.getMessage());
    }

    @Test
    void ofNormalizesNonDeliveredStatusesToEmptyItems() {
        TaskPullResult result = TaskPullResult.of(TaskPullStatus.UNAVAILABLE, List.of(item("msg-1")));

        assertEquals(TaskPullStatus.UNAVAILABLE, result.getStatus());
        assertEquals(List.of(), result.getDispatchViews());
    }

    private static TaskDispatchItem item(String messageId) {
        return new TaskDispatchItem(
                "task-1",
                messageId,
                "crawler.fetch-page",
                "task-name",
                "demoApp",
                "agent",
                0,
                "worker-1",
                null,
                "batch-1",
                Map.of("target", "target-1"),
                Map.of()
        );
    }
}
