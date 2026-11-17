package com.xa.mass.scenario.messages;

import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.task.history.TaskHistoryService;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/storage/messages/tasks")
public final class MessageHistoryController {
    private final TaskHistoryService history;
    public MessageHistoryController(TaskHistoryService history) { this.history = history; }
    @GetMapping("/{historyId}/results")
    public Map<String, Object> results(TaskCaller caller, @PathVariable String historyId) {
        var task = history.requireInput(caller, historyId, "messages", "messages");
        if (!"messages".equals(task.metadata().get("scenario")))
            throw new ServerException(ServerErrorCode.TASK_OPERATION_NOT_SUPPORTED, "messages.history", null, null);
        var preview = history.preview(caller, historyId);
        return Map.of("taskId", task.taskId(), "results", preview.results().stream().map(row -> MessageViews.resultView(task.taskId(), row)).toList(),
                "truncated", preview.truncated());
    }
}
