package com.xa.mass.scenario.messages;

import com.xa.mass.server.api.v1.contract.task.TaskItemResultStatus;
import com.xa.mass.server.project.ProjectTaskQueryService;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.*;
import static com.xa.mass.scenario.messages.MessageInputs.text;
import static com.xa.mass.scenario.messages.MessageWorkerSupply.*;

/** Stateless business projection of existing Task, Item Score and Result observations. */
final class MessageViews {
    private static final Set<String> STAGES = Set.of("SENT", "DELIVERED", "READ", "REPLIED");
    private MessageViews() {}

    static boolean isMessageTask(ProjectTaskQueryService.Entry entry) {
        return entry != null && entry.task() != null && PROJECT.equals(entry.task().projectId()) && "CLOSE_WHEN_IDLE".equals(entry.task().idleDisposition())
                && PROJECT.equals(entry.task().metadata().get("scenario"));
    }
    static Map<String, Object> taskView(ProjectTaskQueryService.Entry entry) {
        var row = new LinkedHashMap<String, Object>();
        row.put("taskId", entry.taskId()); row.put("createdAtMillis", entry.createdAtMillis()); row.put("state", entry.scoreBand());
        row.put("workerGroupId", entry.task() == null ? null : entry.task().workerGroupId());
        row.put("managed", entry.task() != null && "PARK_WHEN_IDLE".equals(entry.task().idleDisposition()));
        if (entry.task() != null && entry.task().name() != null) row.put("name", entry.task().name());
        if (isMessageTask(entry)) {
            var metadata = entry.task().metadata();
            for (String field : List.of("appId", "recipientCountry", "senderPhone", "body", "inputVersion"))
                if (metadata.containsKey(field)) row.put(field, metadata.get(field));
            row.put("senderCountry", metadata.get("senderCountry"));
        }
        return row;
    }
    static void addCounts(Map<String, Object> row, long total, Map<Integer, Long> tags) {
        row.put("sendTotal", total); row.put("sentCount", sum(tags, 6)); row.put("deliveredCount", sum(tags, 7));
        row.put("readCount", sum(tags, 8)); row.put("repliedCount", tags.get(9)); row.put("failedCount", tags.get(5));
    }
    static long sum(Map<Integer, Long> tags, int first) {
        long count = 0; for (int tag = first; tag <= 9; tag++) count += tags.get(tag); return count;
    }
    static Map<String, Object> resultView(String taskId, TaskDataService.ResultEntry entry) {
        var row = new LinkedHashMap<String, Object>();
        row.put("messageId", entry.messageId()); row.put("resultStatus", entry.result().status().wireValue());
        var item = entry.item();
        var parameters = item == null ? Map.<String, Object>of() : item.payload();
        if (parameters.get("recipientId") instanceof String recipient) row.put("recipientId", recipient);
        if (entry.result().status() == TaskItemResultStatus.FAILED) {
            row.put("status", "EXECUTION_FAILED"); return row;
        }
        try {
            var snapshot = Jsons.parseObject(entry.result().opaqueResultPayload());
            if (item == null || !EVENT.equals(item.eventCode())
                    || !taskId.equals(parameters.get("campaignId")) || !entry.messageId().equals(parameters.get("messageId"))
                    || !STAGES.contains(snapshot.get("status"))) throw new IllegalArgumentException();
            for (String key : List.of("campaignId", "messageId", "country", "recipientId", "body"))
                if (!Objects.equals(parameters.get(key), snapshot.get(key))) throw new IllegalArgumentException();
            for (String key : List.of("workerId", "phone")) text(snapshot, key, 256);
            if (!(snapshot.get("observedAtMillis") instanceof Number time) || time.longValue() <= 0) throw new IllegalArgumentException();
            if ("REPLIED".equals(snapshot.get("status"))) {
                text(snapshot, "reply", 4096); text(snapshot, "replyRequestId", 128);
            }
            for (String key : List.of("campaignId", "country", "body", "status", "phone", "workerId", "reply", "replyRequestId", "observedAtMillis"))
                if (snapshot.containsKey(key)) row.put(key, snapshot.get(key));
        } catch (RuntimeException invalid) { row.put("contentError", "消息结果内容无法解析或关联不符"); }
        return row;
    }

}
