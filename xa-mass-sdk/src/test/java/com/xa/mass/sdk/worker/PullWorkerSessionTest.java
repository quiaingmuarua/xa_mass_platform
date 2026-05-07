package com.xa.mass.sdk.worker;

import com.xa.mass.transport.channel.TaskPullChannel;
import com.xa.mass.transport.channel.TaskPullResult;
import com.xa.mass.transport.channel.TaskPullStatus;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.model.TaskDispatchItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PullWorkerSessionTest {

    @Test
    void pollResultDelegatesToTaskPullChannelWithWorkerIdentity() {
        TaskPullChannel taskPullChannel = mock(TaskPullChannel.class);
        TaskPullResult expected = TaskPullResult.delivered(List.of(item("msg-1")));
        when(taskPullChannel.pollTaskMessagesResult("worker-1", 5, 250L)).thenReturn(expected);

        PullWorkerSession session = new PullWorkerSession(
                "worker-1",
                "polling",
                taskPullChannel,
                mock(TaskResultIngestChannel.class),
                mock(WorkerSystemEventChannel.class),
                "polling"
        );

        TaskPullResult result = session.pollResult(5, 250L);

        assertEquals(TaskPullStatus.DELIVERED, result.getStatus());
        assertEquals(List.of("msg-1"), result.getItems().stream().map(TaskDispatchItem::getMessageId).toList());
    }

    @Test
    void pollKeepsLegacyListViewOnTopOfExplicitPullResult() {
        TaskPullChannel taskPullChannel = mock(TaskPullChannel.class);
        when(taskPullChannel.pollTaskMessagesResult("worker-1", 3, 100L))
                .thenReturn(TaskPullResult.delivered(List.of(item("msg-1"), item("msg-2"))));

        PullWorkerSession session = new PullWorkerSession(
                "worker-1",
                "polling",
                taskPullChannel,
                mock(TaskResultIngestChannel.class),
                mock(WorkerSystemEventChannel.class),
                "polling"
        );

        List<TaskDispatchItem> items = session.poll(3, 100L);

        assertEquals(List.of("msg-1", "msg-2"), items.stream().map(TaskDispatchItem::getMessageId).toList());
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
