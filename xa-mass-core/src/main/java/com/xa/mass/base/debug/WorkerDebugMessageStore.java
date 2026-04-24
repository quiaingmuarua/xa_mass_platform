package com.xa.mass.base.debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local debug message store for worker control-event history.
 */
public final class WorkerDebugMessageStore {
    private static final int MAX_HISTORY_PER_WORKER = 120;
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Deque<WorkerDebugMessageRecord>> HISTORY_BY_WORKER = new ConcurrentHashMap<>();
    private static final Map<String, WorkerDebugMessageRecord> RECORD_BY_MESSAGE_ID = new ConcurrentHashMap<>();

    private WorkerDebugMessageStore() {
    }

    public static WorkerDebugMessageRecord recordOutbound(String workerId,
                                                          String project,
                                                          String eventCode,
                                                          String msgType,
                                                          String subMsgType,
                                                          String messageId,
                                                          String payloadJson,
                                                          String rawJson,
                                                          String detail) {
        WorkerDebugMessageRecord record = new WorkerDebugMessageRecord();
        long now = System.currentTimeMillis();
        record.setMessageId(messageId);
        record.setWorkerId(workerId);
        record.setDirection("OUTBOUND");
        record.setProject(project);
        record.setEventCode(normalize(eventCode));
        record.setMsgType(msgType);
        record.setSubMsgType(subMsgType);
        record.setStatus("QUEUED");
        record.setPayloadJson(prettyJson(payloadJson));
        record.setRawJson(prettyJson(rawJson));
        record.setDetail(detail);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        appendRecord(workerId, record);
        RECORD_BY_MESSAGE_ID.put(messageId, record);
        return record;
    }

    public static WorkerDebugMessageRecord recordInbound(String workerId,
                                                         String project,
                                                         String eventCode,
                                                         String msgType,
                                                         String subMsgType,
                                                         String messageId,
                                                         String replyToMessageId,
                                                         String payloadJson,
                                                         String rawJson,
                                                         String detail) {
        WorkerDebugMessageRecord record = new WorkerDebugMessageRecord();
        long now = System.currentTimeMillis();
        record.setMessageId(messageId);
        record.setReplyToMessageId(replyToMessageId);
        record.setWorkerId(workerId);
        record.setDirection("INBOUND");
        record.setProject(project);
        record.setEventCode(resolveInboundEvent(eventCode, replyToMessageId));
        record.setMsgType(msgType);
        record.setSubMsgType(subMsgType);
        record.setStatus("RECEIVED");
        record.setPayloadJson(prettyJson(payloadJson));
        record.setRawJson(prettyJson(rawJson));
        record.setDetail(detail);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        appendRecord(workerId, record);
        RECORD_BY_MESSAGE_ID.put(messageId, record);
        if (replyToMessageId != null && !replyToMessageId.isBlank()) {
            markDelivered(replyToMessageId, detail);
        }
        return record;
    }

    public static void markDelivered(String messageId, String detail) {
        updateStatus(messageId, "DELIVERED", detail);
    }

    public static void markFailed(String messageId, String detail) {
        updateStatus(messageId, "FAILED", detail);
    }

    private static void updateStatus(String messageId, String status, String detail) {
        WorkerDebugMessageRecord record = RECORD_BY_MESSAGE_ID.get(messageId);
        if (record == null) {
            return;
        }
        record.setStatus(status);
        if (detail != null && !detail.isBlank()) {
            record.setDetail(detail);
        }
        record.setUpdatedAt(System.currentTimeMillis());
    }

    public static List<WorkerDebugMessageRecord> getHistory(String workerId) {
        Deque<WorkerDebugMessageRecord> history = HISTORY_BY_WORKER.get(workerId);
        if (history == null) {
            return List.of();
        }
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    public static void clearHistory(String workerId) {
        Deque<WorkerDebugMessageRecord> removed = HISTORY_BY_WORKER.remove(workerId);
        if (removed == null) {
            return;
        }
        synchronized (removed) {
            for (WorkerDebugMessageRecord record : removed) {
                if (record.getMessageId() != null) {
                    RECORD_BY_MESSAGE_ID.remove(record.getMessageId());
                }
            }
        }
    }

    public static void clearAll() {
        HISTORY_BY_WORKER.keySet().forEach(WorkerDebugMessageStore::clearHistory);
        RECORD_BY_MESSAGE_ID.clear();
    }

    private static void appendRecord(String workerId, WorkerDebugMessageRecord record) {
        Deque<WorkerDebugMessageRecord> history = HISTORY_BY_WORKER.computeIfAbsent(workerId, ignored -> new ArrayDeque<>());
        synchronized (history) {
            history.addLast(record);
            while (history.size() > MAX_HISTORY_PER_WORKER) {
                WorkerDebugMessageRecord removed = history.removeFirst();
                if (removed.getMessageId() != null) {
                    RECORD_BY_MESSAGE_ID.remove(removed.getMessageId());
                }
            }
        }
    }

    private static String prettyJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return "{}";
        }
        try {
            JsonElement parsed = JsonParser.parseString(rawJson);
            return PRETTY_GSON.toJson(parsed);
        } catch (Exception ignored) {
            return rawJson;
        }
    }

    private static String resolveInboundEvent(String explicitEventCode, String replyToMessageId) {
        String normalizedExplicit = normalize(explicitEventCode);
        if (normalizedExplicit != null) {
            return normalizedExplicit;
        }
        if (replyToMessageId == null || replyToMessageId.isBlank()) {
            return null;
        }
        WorkerDebugMessageRecord outbound = RECORD_BY_MESSAGE_ID.get(replyToMessageId);
        return outbound == null ? null : normalize(outbound.getEventCode());
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
