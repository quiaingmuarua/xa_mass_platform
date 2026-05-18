package com.xa.mass.testing.soak;

import com.xa.mass.sdk.model.TaskResultItemSnapshot;
import com.xa.mass.sdk.model.TaskResultWindowSnapshot;

import java.util.LinkedHashSet;
import java.util.Set;

final class ResultSequentialReadVerifier {

    @FunctionalInterface
    interface WindowReader {
        TaskResultWindowSnapshot read(String taskId, long afterSeq, int limit);
    }

    private ResultSequentialReadVerifier() {
    }

    static ResultSequentialReadSummary verify(String taskId,
                                              long expectedVisibleResults,
                                              int limit,
                                              WindowReader reader) {
        requireText(taskId, "taskId");
        require(limit > 0, "limit must be positive");
        require(expectedVisibleResults >= 0, "expectedVisibleResults must not be negative");

        long afterSeq = 0L;
        long lastSeq = 0L;
        long totalVisible = -1L;
        int pages = 0;
        Set<String> messageIds = new LinkedHashSet<>();

        while (true) {
            TaskResultWindowSnapshot window = reader.read(taskId, afterSeq, limit);
            require(window != null, "result window must not be null for task=" + taskId);
            require(taskId.equals(window.getTaskId()), "result window taskId mismatch for task=" + taskId);
            if (totalVisible < 0) {
                totalVisible = window.getTotalVisible();
            }
            require(window.getTotalVisible() == totalVisible,
                    "result totalVisible changed while reading task=" + taskId);
            pages++;

            long pageLastSeq = lastSeq;
            for (TaskResultItemSnapshot item : window.getItems()) {
                require(item.getSeq() > pageLastSeq,
                        "result seq must be strictly increasing for task=" + taskId
                                + " seq=" + item.getSeq() + " previous=" + pageLastSeq);
                pageLastSeq = item.getSeq();
                require(messageIds.add(item.getMessageId()),
                        "duplicate result messageId for task=" + taskId + " messageId=" + item.getMessageId());
            }

            if (!window.getItems().isEmpty()) {
                require(window.getNextAfterSeq() == pageLastSeq,
                        "nextAfterSeq must equal last item seq for task=" + taskId
                                + " nextAfterSeq=" + window.getNextAfterSeq() + " lastSeq=" + pageLastSeq);
                lastSeq = pageLastSeq;
                afterSeq = window.getNextAfterSeq();
            } else {
                require(!window.isHasMore(), "empty result window must not report hasMore for task=" + taskId);
            }

            if (!window.isHasMore()) {
                break;
            }
            require(!window.getItems().isEmpty(), "hasMore window must contain progress for task=" + taskId);
            require(pages <= expectedVisibleResults + 1,
                    "too many result pages while reading task=" + taskId + " pages=" + pages);
        }

        require(messageIds.size() == expectedVisibleResults,
                "result visible count mismatch for task=" + taskId
                        + " expected=" + expectedVisibleResults + " actual=" + messageIds.size());
        require(totalVisible == expectedVisibleResults,
                "result totalVisible mismatch for task=" + taskId
                        + " expected=" + expectedVisibleResults + " totalVisible=" + totalVisible);
        return new ResultSequentialReadSummary(taskId, messageIds.size(), pages, lastSeq);
    }

    record ResultSequentialReadSummary(String taskId, long itemCount, int pages, long lastSeq) {
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
