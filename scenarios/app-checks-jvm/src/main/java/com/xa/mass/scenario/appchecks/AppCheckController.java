package com.xa.mass.scenario.appchecks;

import com.xa.mass.server.task.access.TaskCaller;

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
    private final AppCheckConfigurationStore configurations;
    private final AppCheckCredits credits;
    public AppCheckController(AppCheckTaskService tasks, AppCheckConfigurationStore configurations, AppCheckCredits credits) {
        this.tasks = tasks; this.configurations = configurations; this.credits = credits;
    }
    @GetMapping("/configuration") public Object configuration(TaskCaller caller) { caller.requirePermission("app-checks.configuration"); caller.requireProject("app-checks"); return configurations.view(); }
    @PutMapping("/configuration") public Object saveConfiguration(TaskCaller caller, @RequestBody Map<String, Object> input) {
        caller.requirePermission("app-checks.configuration"); caller.requireProject("app-checks");
        final String fingerprint;
        try {
            AppCheckSettings.object(input, java.util.Set.of("expectedFingerprint", "configuration"), java.util.Set.of(), "request");
            fingerprint = AppCheckSpecification.text(input.get("expectedFingerprint"), 64);
        } catch (IllegalArgumentException invalid) {
            throw new AppCheckConfigurationException(400, AppCheckConfigurationException.Code.INVALID_CONFIGURATION,
                    "save", invalid.getMessage(), invalid);
        }
        return configurations.save(fingerprint, input.get("configuration"));
    }
    @ExceptionHandler(AppCheckConfigurationException.class)
    ResponseEntity<?> configurationFailure(AppCheckConfigurationException failure) {
        return ResponseEntity.status(failure.status).body(Map.of("errorCode", failure.code.name(), "message", failure.getMessage()));
    }
    @GetMapping("/catalog") public Object catalog() { return tasks.catalog(); }
    @PostMapping("/tasks") public ResponseEntity<?> create(TaskCaller caller, @RequestBody Map<String, Object> input) {
        return ResponseEntity.status(201).body(tasks.create(caller, input));
    }
    @GetMapping("/tasks/{taskId}/credits")
    public AppCheckCredits.View credits(TaskCaller caller, @PathVariable String taskId, @RequestParam(required = false) String requestId) {
        return credits.view(caller, taskId, requestId);
    }
    @GetMapping("/tasks/{taskId}/results")
    public Object results(TaskCaller caller,
            @PathVariable String taskId) { return tasks.results(caller, taskId); }
    @PostMapping(value = "/tasks/{taskId}/seeds:import", consumes = "text/plain")
    public Object importSeeds(TaskCaller caller, @PathVariable String taskId, InputStream input) { return tasks.importSeeds(caller, taskId, input); }
    @PostMapping("/tasks/{taskId}/results:export")
    public ResponseEntity<StreamingResponseBody> export(TaskCaller caller, @PathVariable String taskId, @RequestParam(defaultValue = "all") String filter) {
        var file = tasks.export(caller, taskId, filter);
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
