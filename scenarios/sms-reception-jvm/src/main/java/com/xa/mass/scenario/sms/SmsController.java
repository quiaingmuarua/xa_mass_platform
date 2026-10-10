package com.xa.mass.scenario.sms;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sms")
public class SmsController {
    private final SmsReceptionService reception;
    SmsController(SmsReceptionService reception) { this.reception = reception; }
    @GetMapping("/catalog") public Object catalog() { return reception.catalog(); }
    @PostMapping("/numbers:lease") public Object lease(@RequestBody Map<String, Object> input) { return reception.lease(input); }
    @GetMapping("/messages/{messageId}") public Object get(@PathVariable String messageId) { return reception.get(messageId); }
    @GetMapping("/metrics") public Object metrics() { return reception.metrics(); }
    @ExceptionHandler(SmsReceptionService.ProductError.class) ResponseEntity<?> productError(SmsReceptionService.ProductError error) {
        return ResponseEntity.status(error.status).body(Map.of("message", error.getMessage()));
    }
    @ExceptionHandler(IllegalStateException.class) ResponseEntity<?> unavailable(IllegalStateException error) {
        return ResponseEntity.status(503).body(Map.of("message", "Product unavailable"));
    }
}
