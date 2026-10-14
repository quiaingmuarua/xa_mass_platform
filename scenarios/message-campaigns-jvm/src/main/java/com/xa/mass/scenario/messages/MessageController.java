package com.xa.mass.scenario.messages;

import com.xa.mass.server.task.access.TaskCaller;

import java.util.Map;
import java.io.InputStream;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/messages")
public class MessageController {
    private final MessageTaskService tasks;
    MessageController(MessageTaskService tasks) { this.tasks = tasks; }
    @GetMapping("/catalog") public Object catalog() { return tasks.catalog(); }
    @PostMapping("/tasks") public ResponseEntity<?> create(TaskCaller caller, @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(201).body(tasks.create(caller, body));
    }
    @PostMapping(value = "/tasks/{taskId}/recipients:import", consumes = "text/plain")
    public Object importRecipients(TaskCaller caller, @PathVariable String taskId, InputStream input) { return tasks.importRecipients(caller, taskId, input); }
    @GetMapping("/tasks/{taskId}/results")
    public Object results(TaskCaller caller,
            @PathVariable String taskId) { return tasks.results(caller, taskId); }
    @ExceptionHandler(MessageError.class) ResponseEntity<?> error(MessageError error) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("message", error.getMessage());
        if (error.taskId != null) body.put("taskId", error.taskId);
        if (error.confirmedAddedCount != null) body.put("confirmedAddedCount", error.confirmedAddedCount);
        if (error.existingCount != null) body.put("existingCount", error.existingCount);
        return ResponseEntity.status(error.status).body(body);
    }
}
