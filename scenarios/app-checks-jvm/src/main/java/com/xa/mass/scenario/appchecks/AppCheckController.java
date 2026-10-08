package com.xa.mass.scenario.appchecks;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.InputStream;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ContentDisposition;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import com.xa.mass.server.operation.OperationAlreadyRunningException;

@RestController
@RequestMapping("/api/v1/app-checks")
public final class AppCheckController {
    private final AppCheckTaskService tasks;
    public AppCheckController(AppCheckTaskService tasks) { this.tasks = tasks; }
    @GetMapping("/catalog") public Object catalog() { return tasks.catalog(); }
    @PostMapping("/tasks") public ResponseEntity<?> create(@RequestBody Map<String, Object> input) {
        return ResponseEntity.status(201).body(tasks.create(input));
    }
    @GetMapping("/tasks") public Object list(@RequestParam(defaultValue = "100") int limit) { return tasks.list(limit); }
    @GetMapping("/tasks/{taskId}") public Object get(@PathVariable String taskId) { return tasks.get(taskId); }
    @PostMapping(value = "/tasks/{taskId}/numbers:import", consumes = "text/plain")
    public Object importNumbers(@PathVariable String taskId, InputStream input) { return tasks.importNumbers(taskId, input); }
    @PostMapping("/tasks/{taskId}/approve")
    public Object approve(@PathVariable String taskId, @RequestBody long expectedCount) { return tasks.approve(taskId, expectedCount); }
    @PostMapping("/tasks/{taskId}/close")
    public Object close(@PathVariable String taskId) { return tasks.closeTask(taskId); }
    @PostMapping("/tasks/{taskId}/results:export")
    public ResponseEntity<StreamingResponseBody> export(@PathVariable String taskId, @RequestParam(defaultValue = "all") String filter) {
        var file = tasks.export(taskId, filter);
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .header("X-Export-Count", Long.toString(file.count()))
                .body(output -> tasks.transferExport(file, output));
    }
    @ExceptionHandler(OperationAlreadyRunningException.class)
    ResponseEntity<?> busy() { return ResponseEntity.status(409).body(Map.of("message", "任务导出正在进行，请稍后重试")); }
    @ExceptionHandler(AppCheckTaskService.RequestFailure.class)
    ResponseEntity<?> failure(AppCheckTaskService.RequestFailure failure) {
        var body = new LinkedHashMap<String, Object>();
        body.put("message", failure.getMessage());
        if (failure.taskId != null) body.put("taskId", failure.taskId);
        if (failure.confirmedAdded != null) body.put("confirmedAddedCount", failure.confirmedAdded);
        if (failure.existing != null) body.put("existingCount", failure.existing);
        return ResponseEntity.status(failure.status).body(body);
    }
}
