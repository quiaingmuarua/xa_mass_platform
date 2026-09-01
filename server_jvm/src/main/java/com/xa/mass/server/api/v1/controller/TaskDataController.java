package com.xa.mass.server.api.v1.controller;

import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.api.v1.contract.ActionOutcome;
import com.xa.mass.server.api.v1.contract.ApiErrorResponse;
import com.xa.mass.server.api.v1.contract.task.TaskItemRequest;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultResponse;
import com.xa.mass.server.api.v1.contract.task.TaskRpcCallRequest;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.call.TaskRpcCallService;
import com.xa.mass.server.task.result.TaskResultsExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Tag(name = ApiTags.TASKS)
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskDataController {

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

    @Operation(
            summary = "Call a managed Task",
            description = "Submission is accepted before synchronous Result "
                    + "observation. Items not observed within the wait budget "
                    + "or observation capacity are represented by the "
                    + "not_observed outcome and can be loaded later by "
                    + "messageId."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Submission was accepted; each Item is "
                            + "succeeded, failed, or not_observed",
                    content = @Content(schema = @Schema(
                            type = "object",
                            additionalPropertiesSchema =
                                    TaskItemResultResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Task business request was rejected",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Task Owner is temporarily unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            )
    })
    @PostMapping("/{taskId}/items:call")
    public DeferredResult<Map<String, TaskItemResultResponse>> callTaskItems(
            @PathVariable @NotBlank String taskId,
            @Valid @RequestBody TaskRpcCallRequest request
    ) {
        return taskCall.call(taskId, request);
    }

    @Operation(summary = "Append Items to a finite Task")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Applied or rejected Action outcomes keyed "
                            + "by messageId",
                    content = @Content(schema = @Schema(
                            type = "object",
                            additionalPropertiesSchema =
                                    ActionOutcome.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Task business request was rejected",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Task Owner is temporarily unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            )
    })
    @PostMapping("/{taskId}/items")
    public ResponseEntity<Map<String, ActionOutcome>> appendTaskItems(
            @PathVariable @NotBlank String taskId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(array = @ArraySchema(
                            minItems = 1,
                            maxItems = 100,
                            schema = @Schema(
                                    implementation = TaskItemRequest.class
                            )
                    ))
            )
            @RequestBody
            @NotNull @Size(min = 1, max = 100)
            List<@NotNull @Valid TaskItemRequest> items
    ) {
        return ResponseEntity.ok(taskData.appendFiniteTaskItems(
                taskId,
                List.copyOf(items)
        ));
    }

    @Operation(
            summary = "Load TaskItem result states",
            description = "Returns succeeded, failed, or not_observed for "
                    + "every requested messageId. Only succeeded contains "
                    + "opaqueResultPayload."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Result states keyed by messageId",
                    content = @Content(schema = @Schema(
                            type = "object",
                            additionalPropertiesSchema =
                                    TaskItemResultResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Task business request was rejected",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Task Owner is temporarily unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            )
    })
    @PostMapping("/{taskId}/results:load")
    public ResponseEntity<Map<String, TaskItemResultResponse>>
            loadTaskItemResults(
            @PathVariable @NotBlank String taskId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(array = @ArraySchema(
                            minItems = 1,
                            maxItems = 1000,
                            schema = @Schema(type = "string", minLength = 1)
                    ))
            )
            @RequestBody
            @NotNull @Size(min = 1, max = 1000)
            List<@NotBlank String> messageIds
    ) {
        return ResponseEntity.ok(taskData.loadTaskItemResults(
                taskId,
                List.copyOf(messageIds)
        ));
    }

    @Operation(
            summary = "Export successful finite Task results",
            description = "The Task score is observed once. A non-terminal "
                    + "Task returns TASK_RESULTS_NOT_READY immediately."
    )
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
                    responseCode = "400",
                    description = "Task business request was rejected",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Task Owner is temporarily unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            )
    })
    @PostMapping("/{taskId}/results:export")
    public ResponseEntity<StreamingResponseBody> exportTaskItemResults(
            @PathVariable @NotBlank String taskId
    ) {
        var export = taskResultsExport.export(taskId);
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
