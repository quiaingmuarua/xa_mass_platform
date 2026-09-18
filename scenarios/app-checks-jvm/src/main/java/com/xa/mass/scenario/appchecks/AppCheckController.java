package com.xa.mass.scenario.appchecks;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    @ExceptionHandler(AppCheckTaskService.RequestFailure.class)
    ResponseEntity<?> failure(AppCheckTaskService.RequestFailure failure) {
        var body = new LinkedHashMap<String, Object>();
        body.put("message", failure.getMessage());
        if (failure.taskId != null) body.put("taskId", failure.taskId);
        return ResponseEntity.status(failure.status).body(body);
    }
}
