package com.xa.mass.messages.backend;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/messages")
public class MessageController {
    private final CampaignService campaigns;
    MessageController(CampaignService campaigns) { this.campaigns = campaigns; }
    @GetMapping("/catalog") public Object catalog() { return campaigns.catalog(); }
    @PostMapping("/campaigns") public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        return ResponseEntity.accepted().body(campaigns.create(body));
    }
    @GetMapping("/campaigns") public Object page(@RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "30") int limit) { return campaigns.page(offset, limit); }
    @GetMapping("/campaigns/{id}") public Object get(@PathVariable String id) { return campaigns.get(id); }
    @GetMapping("/campaigns/{id}/messages") public Object messages(@PathVariable String id,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "30") int limit) {
        return campaigns.messages(id, offset, limit);
    }
    @GetMapping("/metrics") public Object metrics() { return campaigns.metrics(); }
    @ExceptionHandler(CampaignService.ProductError.class) ResponseEntity<?> error(CampaignService.ProductError error) {
        return ResponseEntity.status(error.status).body(Map.of("message", error.getMessage()));
    }
}
