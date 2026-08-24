package com.xa.mass.server.api.v1.taskbatch;

import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.api.v1.taskbatch.model.TaskBatchInputUploadResponse;
import com.xa.mass.server.api.v1.taskbatch.model.TaskBatchRunRequest;
import com.xa.mass.server.api.v1.taskbatch.model.TaskBatchRunResponse;
import com.xa.mass.server.taskbatch.TaskBatchService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.TASK_BATCH_LAB)
@Validated
@RestController
@Profile("scenario-workers")
@RequestMapping("/api/v1/task-batches")
public class TaskBatchController {

    private static final MediaType NDJSON = new MediaType(
            "application",
            "x-ndjson"
    );

    private final TaskBatchService taskBatch;

    public TaskBatchController(TaskBatchService taskBatch) {
        this.taskBatch = taskBatch;
    }

    @PostMapping(
            path = "/input-files/{fileName}",
            consumes = MediaType.TEXT_PLAIN_VALUE
    )
    public TaskBatchInputUploadResponse upload(
            @PathVariable @NotBlank String fileName,
            @RequestBody byte[] content
    ) {
        return taskBatch.upload(fileName, content);
    }

    @PostMapping("/runs")
    public TaskBatchRunResponse run(
            @RequestBody TaskBatchRunRequest request
    ) {
        return taskBatch.run(request);
    }

    @GetMapping("/output-files/{fileName}")
    public ResponseEntity<byte[]> download(
            @PathVariable @NotBlank String fileName
    ) {
        return ResponseEntity.ok()
                .contentType(NDJSON)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(fileName)
                                .build()
                                .toString()
                )
                .body(taskBatch.download(fileName));
    }
}
