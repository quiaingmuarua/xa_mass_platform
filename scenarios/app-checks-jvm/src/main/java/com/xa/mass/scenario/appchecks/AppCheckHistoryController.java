package com.xa.mass.scenario.appchecks;

import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.task.history.TaskHistoryService;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/storage/app-checks/tasks")
public final class AppCheckHistoryController {
    private final TaskHistoryService history;
    private final AppCheckContracts contracts;
    public AppCheckHistoryController(TaskHistoryService history, AppCheckContracts contracts) {
        this.history = history; this.contracts = contracts;
    }
    @GetMapping("/{historyId}/results")
    public Map<String, Object> results(TaskCaller caller, @PathVariable String historyId) {
        var task = require(caller, historyId);
        return AppCheckResultViews.preview(task.taskId(), snapshot(task), history.preview(caller, historyId));
    }
    @PostMapping("/{historyId}/results:export")
    public ResponseEntity<StreamingResponseBody> export(TaskCaller caller, @PathVariable String historyId,
            @RequestParam(defaultValue = "all") String filter) {
        var task = require(caller, historyId);
        var file = AppCheckResultViews.export(task.taskId(), snapshot(task), filter, consumer -> history.forEachResult(caller, historyId, consumer));
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .header("X-Export-Count", Long.toString(file.count()))
                .body(output -> history.transferAndDelete(file.file(), output));
    }
    private TaskDescriptor require(TaskCaller caller, String id) {
        var task = history.requireInput(caller, id, "app-checks", "app-checks");
        if (!"app-checks".equals(task.metadata().get("scenario")))
            throw new ServerException(ServerErrorCode.TASK_OPERATION_NOT_SUPPORTED, "appChecks.history", null, null);
        return task;
    }
    private AppCheckTaskSnapshot snapshot(TaskDescriptor task) {
        try { return AppCheckTaskSnapshot.read(task.workerGroupId(), task.metadata(), contracts); }
        catch (IllegalArgumentException invalid) {
            throw new ServerException(ServerErrorCode.TASK_OPERATION_NOT_SUPPORTED, "appChecks.historyContract", "Saved check contract is unavailable", invalid);
        }
    }
    @ExceptionHandler(AppCheckTaskService.RequestFailure.class)
    ResponseEntity<?> failure(AppCheckTaskService.RequestFailure failure) {
        return ResponseEntity.status(failure.status).body(Map.of("message", failure.getMessage()));
    }
}
