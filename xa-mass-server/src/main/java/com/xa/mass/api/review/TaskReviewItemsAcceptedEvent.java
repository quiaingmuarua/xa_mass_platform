package com.xa.mass.api.review;

import com.xa.mass.sdk.model.TaskItemBatchAppendReceipt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of accepted task items for review/export materialization.
 */
public record TaskReviewItemsAcceptedEvent(String taskId,
                                           List<Map<String, Object>> acceptedItems,
                                           List<String> messageIds,
                                           int added,
                                           int maxRetryCount)
        implements TaskReviewReportEvent {

    public TaskReviewItemsAcceptedEvent {
        acceptedItems = copyItems(acceptedItems);
        messageIds = messageIds == null ? List.of() : List.copyOf(messageIds);
        added = Math.max(0, added);
        maxRetryCount = Math.max(0, maxRetryCount);
    }

    public static TaskReviewItemsAcceptedEvent from(String taskId,
                                                    List<Map<String, Object>> acceptedItems,
                                                    TaskItemBatchAppendReceipt receipt,
                                                    int maxRetryCount) {
        List<String> messageIds = receipt == null ? List.of() : receipt.messageIds();
        int added = receipt == null ? 0 : receipt.added();
        return new TaskReviewItemsAcceptedEvent(taskId, acceptedItems, messageIds, added, maxRetryCount);
    }

    private static List<Map<String, Object>> copyItems(List<Map<String, Object>> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> copy = new ArrayList<>(source.size());
        for (Map<String, Object> item : source) {
            if (item == null || item.isEmpty()) {
                copy.add(Map.of());
            } else {
                copy.add(Collections.unmodifiableMap(new LinkedHashMap<>(item)));
            }
        }
        return List.copyOf(copy);
    }
}
