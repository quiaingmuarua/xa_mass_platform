package com.xa.mass.server.api.v1.controller;

import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.task.history.TaskHistoryService;
import com.xa.mass.server.task.history.TaskHistoryStore.Page;
import com.xa.mass.server.task.TaskDataService.ResultPreview;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Tag(name = com.xa.mass.server.api.ApiTags.TASKS)
@io.swagger.v3.oas.annotations.responses.ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Saved history read"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid query or history is missing or expired", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = com.xa.mass.server.api.v1.contract.ApiErrorResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "History access denied", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = com.xa.mass.server.api.v1.contract.ApiErrorResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "History storage unavailable", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = com.xa.mass.server.api.v1.contract.ApiErrorResponse.class)))
})
@RestController
@RequestMapping("/api/v1/storage/tasks")
public final class TaskHistoryController {
    private final TaskHistoryService history;
    public TaskHistoryController(TaskHistoryService history) { this.history = history; }

    @Operation(summary = "List immutable finite Task history in the current scope", description = "Storage only. Authorization filters precede cursor pagination; no Runtime fallback.")
    @GetMapping
    public Page<TaskHistoryService.Entry> list(TaskCaller caller,
            @RequestParam(required = false) String projectId, @RequestParam(required = false) String userId,
            @RequestParam(required = false) String taskId, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return history.list(caller, projectId, userId, taskId, cursor, limit);
    }
    @Operation(summary = "Read a saved finite Task")
    @GetMapping("/{historyId}")
    public TaskHistoryService.Entry get(TaskCaller caller, @PathVariable String historyId) { return history.get(caller, historyId); }

    @Operation(summary = "Page saved Item inputs, independent states and observed Results")
    @GetMapping("/{historyId}/items")
    public Page<TaskHistoryService.Item> items(TaskCaller caller, @PathVariable String historyId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        return history.items(caller, historyId, cursor, limit);
    }
    @Operation(summary = "Preview at most 100 observed historical Results")
    @GetMapping("/{historyId}/results")
    public ResultPreview preview(TaskCaller caller, @PathVariable String historyId) { return history.preview(caller, historyId); }

    @Operation(summary = "Export every successful historical Result as JSONL")
    @PostMapping("/{historyId}/results:export")
    public ResponseEntity<StreamingResponseBody> export(TaskCaller caller, @PathVariable String historyId) {
        var file = history.export(caller, historyId).file();
        return ResponseEntity.ok().contentType(new MediaType("application", "x-ndjson"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(historyId + "-results.jsonl").build().toString())
                .body(output -> history.transferAndDelete(file, output));
    }
}
