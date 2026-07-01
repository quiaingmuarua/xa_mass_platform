package com.xa.mass.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskRuntimeAppendItemMapperTest {

    @Test
    void mapsIngressItemWithoutLeakingControlFieldsIntoPayload() {
        var ingressItem = RuntimeTaskIngressItem.fromInput(
                "task-1",
                "message-1",
                Map.of(
                        "eventCode", "demo.event",
                        "payloadRef", "payload-ref-1",
                        "value", 1),
                3);

        var appendItem = TaskRuntimeAppendItemMapper.toAppendItem(ingressItem);

        assertThat(appendItem.messageId()).isEqualTo("message-1");
        assertThat(appendItem.eventCode()).isEqualTo("demo.event");
        assertThat(appendItem.payloadRef()).isEqualTo("payload-ref-1");
        assertThat(appendItem.payloadJson()).containsEntry("value", 1);
        assertThat(appendItem.payloadJson()).doesNotContainKeys("eventCode", "payloadRef");
    }

    @Test
    void mapsBatchInOrder() {
        var mapped = TaskRuntimeAppendItemMapper.toAppendItems(List.of(
                RuntimeTaskIngressItem.fromInput("task-1", "message-1", Map.of("value", 1), 3),
                RuntimeTaskIngressItem.fromInput("task-1", "message-2", Map.of("value", 2), 3)));

        assertThat(mapped)
                .extracting(item -> item.messageId())
                .containsExactly("message-1", "message-2");
    }
}
