package com.xa.mass.engine;

import com.xa.mass.task.runtime.AppendItemInput;

import java.util.List;

final class TaskRuntimeAppendItemMapper {

    private TaskRuntimeAppendItemMapper() {
    }

    static List<AppendItemInput> toAppendItems(List<RuntimeTaskIngressItem> ingressItems) {
        if (ingressItems == null || ingressItems.isEmpty()) {
            return List.of();
        }
        return ingressItems.stream()
                .map(TaskRuntimeAppendItemMapper::toAppendItem)
                .toList();
    }

    static AppendItemInput toAppendItem(RuntimeTaskIngressItem ingressItem) {
        if (ingressItem == null) {
            throw new IllegalArgumentException("ingressItem is required");
        }
        return new AppendItemInput(
                ingressItem.messageId(),
                ingressItem.eventCode(),
                ingressItem.inlinePayload(),
                ingressItem.payloadRef());
    }
}
