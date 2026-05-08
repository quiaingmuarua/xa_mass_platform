package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.sdk.TransportOperations;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/runtime")
@Tag(name = "Session Status")
public class SessionController {

    private final TransportOperations transportOperations;

    public SessionController(TransportOperations transportOperations) {
        this.transportOperations = transportOperations;
    }

    @GetMapping("/sessions")
    @Operation(summary = "List all active worker sessions")
    public ApiResponse<List<Map<String, Object>>> listSessions() {
        return ApiResponse.success(transportOperations.listSessions());
    }

    @GetMapping("/sessions:stats")
    @Operation(summary = "Get aggregate session statistics")
    public ApiResponse<Map<String, Object>> sessionStats() {
        return ApiResponse.success(transportOperations.getSessionStats());
    }
}
