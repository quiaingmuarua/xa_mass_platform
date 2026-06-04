package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.sdk.RuntimeDiagnosticsOperations;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/runtime")
@Tag(name = "Session Status")
public class SessionController {

    private static final int DEFAULT_DIAGNOSTIC_LIMIT = 200;
    private static final int MAX_DIAGNOSTIC_LIMIT = 500;

    private final RuntimeDiagnosticsOperations runtimeDiagnostics;

    public SessionController(RuntimeDiagnosticsOperations runtimeDiagnostics) {
        this.runtimeDiagnostics = runtimeDiagnostics;
    }

    @GetMapping("/sessions")
    @Operation(summary = "List all active worker sessions")
    public ApiResponse<List<Map<String, Object>>> listSessions(@RequestParam(required = false) Integer limit) {
        int resolvedLimit = resolveDiagnosticLimit(limit);
        return ApiResponse.success(runtimeDiagnostics.listSessions().stream()
                .limit(resolvedLimit)
                .toList());
    }

    @GetMapping("/sessions:stats")
    @Operation(summary = "Get aggregate session statistics")
    public ApiResponse<Map<String, Object>> sessionStats() {
        return ApiResponse.success(runtimeDiagnostics.getSessionStats());
    }

    private int resolveDiagnosticLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_DIAGNOSTIC_LIMIT;
        }
        return Math.min(limit, MAX_DIAGNOSTIC_LIMIT);
    }
}
