package com.xa.mass.scenario.messages;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/messages")
public class MessageController {
    private final MessageTaskService tasks;
    MessageController(MessageTaskService tasks) { this.tasks = tasks; }
    @GetMapping("/catalog") public Object catalog() { return tasks.catalog(); }
    @PostMapping("/tasks") public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(201).body(tasks.create(body));
    }
    @GetMapping("/tasks") public Object list(@RequestParam(defaultValue = "100") int limit) { return tasks.list(limit); }
    @GetMapping("/tasks/{taskId}") public Object get(@PathVariable String taskId) { return tasks.get(taskId); }
    @ExceptionHandler(MessageTaskService.ProductError.class) ResponseEntity<?> error(MessageTaskService.ProductError error) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("message", error.getMessage());
        if (error.taskId != null) body.put("taskId", error.taskId);
        return ResponseEntity.status(error.status).body(body);
    }
}
