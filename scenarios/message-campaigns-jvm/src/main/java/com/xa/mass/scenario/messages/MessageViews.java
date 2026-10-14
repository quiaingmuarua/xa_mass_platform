package com.xa.mass.scenario.messages;

import com.xa.mass.server.api.v1.contract.task.TaskItemResultStatus;
import com.xa.mass.server.project.ProjectTaskQueryService;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.*;
import static com.xa.mass.scenario.messages.MessageInputs.text;
import static com.xa.mass.scenario.messages.MessageWorkerSupply.*;

/** Stateless business Result projection with Task and Item association checks. */
final class MessageViews {
    private static final Set<String> STAGES = Set.of("SENT", "DELIVERED", "READ", "REPLIED");
    private MessageViews() {}

    static boolean isMessageTask(ProjectTaskQueryService.Entry entry) {
        return entry != null && entry.task() != null && PROJECT.equals(entry.task().projectId()) && "CLOSE_WHEN_IDLE".equals(entry.task().idleDisposition())
                && PROJECT.equals(entry.task().metadata().get("scenario"))
                && PROJECT.equals(entry.task().metadata().get("server.inputOwner"));
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
