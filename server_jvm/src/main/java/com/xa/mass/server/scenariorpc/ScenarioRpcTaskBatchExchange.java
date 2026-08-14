package com.xa.mass.server.scenariorpc;

import com.xa.mass.scenariorpc.ScenarioRpcBatchExchange;
import com.xa.mass.scenariorpc.ScenarioRpcDescriptor;
import com.xa.mass.scenariorpc.ScenarioRpcItem;
import com.xa.mass.server.api.v1.model.RuntimeCommandStatus;
import com.xa.mass.server.api.v1.model.TaskItemRequest;
import com.xa.mass.server.api.v1.model.TaskItemsAppendRequest;
import com.xa.mass.server.api.v1.model.TaskItemsAppendResponse;
import com.xa.mass.server.taskdata.TaskDataService;
import com.xa.mass.server.taskdata.WorkerGroupTaskCatalog;
import com.xa.mass.workerdelivery.json.Jsons;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ScenarioRpcTaskBatchExchange
        implements ScenarioRpcBatchExchange {

    private final TaskDataService taskData;
    private final WorkerGroupTaskCatalog taskCatalog;
    private final Clock clock;

    ScenarioRpcTaskBatchExchange(
            TaskDataService taskData,
            WorkerGroupTaskCatalog taskCatalog,
            Clock clock
    ) {
        this.taskData = Objects.requireNonNull(taskData, "taskData");
        this.taskCatalog = Objects.requireNonNull(taskCatalog, "taskCatalog");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void append(
            ScenarioRpcDescriptor scenario,
            List<ScenarioRpcItem> items
    ) {
        String taskId = taskId(scenario);
        long createdAtMillis = clock.millis();
        List<TaskItemRequest> requests = items.stream()
                .map(item -> new TaskItemRequest(
                        item.messageId(),
                        scenario.eventCode(),
                        createdAtMillis,
                        item.payload(),
                        5,
                        null,
                        Map.of()
                ))
                .toList();
        TaskItemsAppendResponse appended = taskData.appendTaskItems(
                taskId,
                new TaskItemsAppendRequest(requests)
        );
        for (ScenarioRpcItem item : items) {
            var result = appended.results().get(item.messageId());
            if (result == null
                    || result.status() != RuntimeCommandStatus.APPENDED) {
                throw new IllegalStateException(
                        "Scenario RPC batch append was not accepted"
                );
            }
        }
    }

    @Override
    public Map<String, Map<String, Object>> loadResults(
            ScenarioRpcDescriptor scenario,
            List<String> pendingMessageIds
    ) {
        Map<String, String> loaded = taskData.loadTaskItemSuccessResults(
                taskId(scenario),
                pendingMessageIds
        ).results();
        Map<String, Map<String, Object>> decoded = new LinkedHashMap<>();
        for (String messageId : pendingMessageIds) {
            String payload = loaded.get(messageId);
            if (payload != null) {
                decoded.put(messageId, Jsons.parseObject(payload));
            }
        }
        return decoded;
    }

    private String taskId(ScenarioRpcDescriptor scenario) {
        String taskId = taskCatalog.taskIdsByWorkerGroup().get(
                scenario.workerGroupId()
        );
        if (taskId == null) {
            throw new IllegalStateException(
                    "Scenario RPC WorkerGroup has no internal Task"
            );
        }
        return taskId;
    }
}
