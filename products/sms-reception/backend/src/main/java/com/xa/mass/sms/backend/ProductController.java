package com.xa.mass.sms.backend;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sms")
public class ProductController {
    private final ListenerService listeners;
    ProductController(ListenerService listeners) { this.listeners = listeners; }
    @GetMapping("/catalog") public Object catalog() { return listeners.catalog(); }
    @PostMapping("/listeners") public Object create(@RequestBody Map<String, Object> input) { return listeners.create(input); }
    @GetMapping("/listeners") public Object page(@RequestParam(defaultValue = "0") int offset,
                                                       @RequestParam(defaultValue = "30") int limit) { return listeners.page(offset, limit); }
    @GetMapping("/listeners/{id}") public Object get(@PathVariable String id) { return listeners.get(id); }
    @PostMapping("/listeners/{id}/cancel") public Object cancel(@PathVariable String id) { return listeners.cancel(id); }
    @GetMapping("/metrics") public Object metrics() { return listeners.metrics(); }
    @ExceptionHandler(ListenerService.ProductError.class) ResponseEntity<?> productError(ListenerService.ProductError error) {
        return ResponseEntity.status(error.status).body(Map.of("message", error.getMessage()));
    }
    @ExceptionHandler(IllegalStateException.class) ResponseEntity<?> unavailable(IllegalStateException error) {
        return ResponseEntity.status(503).body(Map.of("message", "Product unavailable"));
    }
}
