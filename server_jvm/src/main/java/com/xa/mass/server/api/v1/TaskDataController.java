package com.xa.mass.server.api.v1;

import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.api.v1.model.TaskItemResultsLoadRequest;
import com.xa.mass.server.api.v1.model.TaskItemResultsLoadResponse;
import com.xa.mass.server.api.v1.model.TaskItemsAppendRequest;
import com.xa.mass.server.api.v1.model.TaskItemsAppendResponse;
import com.xa.mass.server.api.v1.model.TaskRpcCallRequest;
import com.xa.mass.server.api.v1.model.TaskRpcCallResponse;
import com.xa.mass.server.api.v1.model.TaskResultsExportNotReadyResponse;
import com.xa.mass.server.api.v1.model.TaskResultsExportRequest;
import com.xa.mass.server.taskdata.TaskDataService;
import com.xa.mass.server.taskdata.TaskRpcCallService;
import com.xa.mass.server.taskdata.TaskResultsExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Tag(name = ApiTags.TASKS)
@Validated
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskDataController {

    private static final byte[] NOT_READY_RESPONSE =
            ("{\"status\":\""
                    + TaskResultsExportNotReadyResponse.STATUS
                    + "\"}").getBytes(StandardCharsets.UTF_8);

    private final TaskDataService taskData;
    private final TaskRpcCallService taskCall;
    private final TaskResultsExportService taskResultsExport;

    public TaskDataController(
            TaskDataService taskData,
            TaskRpcCallService taskCall,
            TaskResultsExportService taskResultsExport
    ) {
        this.taskData = taskData;
        this.taskCall = taskCall;
        this.taskResultsExport = taskResultsExport;
    }

    @Operation(summary = "Call a managed Task")
    @PostMapping("/{taskId}/items:call")
    public DeferredResult<ResponseEntity<TaskRpcCallResponse>> callTaskItems(
            @PathVariable @NotBlank String taskId,
            @Valid @RequestBody TaskRpcCallRequest request
    ) {
        return taskCall.call(taskId, request);
    }

    @Operation(summary = "Append Items to a finite Task")
    @PostMapping("/{taskId}/items")
    public ResponseEntity<TaskItemsAppendResponse> appendTaskItems(
            @PathVariable @NotBlank String taskId,
            @Valid @RequestBody TaskItemsAppendRequest request
    ) {
        return ResponseEntity.ok(taskData.appendFiniteTaskItems(
                taskId,
                request
        ));
    }

    @Operation(summary = "Load successful Task results")
    @PostMapping("/{taskId}/results:load")
    public ResponseEntity<TaskItemResultsLoadResponse> loadTaskItemResults(
            @PathVariable @NotBlank String taskId,
            @Valid @RequestBody TaskItemResultsLoadRequest request
    ) {
        return ResponseEntity.ok(taskData.loadTaskItemSuccessResults(
                taskId,
                request.messageIds()
        ));
    }

    @Operation(summary = "Export successful finite Task results")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Task is terminal; successful Results are streamed",
                    content = @Content(
                            mediaType = "application/x-ndjson",
                            schema = @Schema(type = "string", format = "binary")
                    )
            ),
            @ApiResponse(
                    responseCode = "202",
                    description = "Task was not observed terminal within the wait budget",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(
                                    implementation = TaskResultsExportNotReadyResponse.class
                            )
                    )
            )
    })
    @PostMapping("/{taskId}/results:export")
    public ResponseEntity<StreamingResponseBody> exportTaskItemResults(
            @PathVariable @NotBlank String taskId,
            @Valid @RequestBody TaskResultsExportRequest request
    ) {
        var export = taskResultsExport.export(
                taskId,
                request.waitTimeoutMillis()
        );
        if (!export.ready()) {
            StreamingResponseBody body = output ->
                    output.write(NOT_READY_RESPONSE);
            return ResponseEntity.accepted()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
        }
        StreamingResponseBody body = output ->
                taskResultsExport.transferAndDelete(export.file(), output);
        return ResponseEntity.ok()
                .contentType(new MediaType("application", "x-ndjson"))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(taskId + "-results.jsonl")
                                .build()
                                .toString()
                )
                .body(body);
    }
}
