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
import java.util.LinkedHashMap;
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
                .map(this::safeSessionView)
                .limit(resolvedLimit)
                .toList());
    }

    @GetMapping("/sessions:stats")
    @Operation(summary = "Get aggregate session statistics")
    public ApiResponse<Map<String, Object>> sessionStats() {
        return ApiResponse.success(safeSessionStats(runtimeDiagnostics.getSessionStats()));
    }

    private int resolveDiagnosticLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_DIAGNOSTIC_LIMIT;
        }
        return Math.min(limit, MAX_DIAGNOSTIC_LIMIT);
    }

    private Map<String, Object> safeSessionView(Map<String, Object> session) {
        if (session == null || session.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("workerId", session.get("workerId"));
        safe.put("connections", safeConnections(session.get("connections")));
        return safe;
    }

    private List<Map<String, Object>> safeConnections(Object connections) {
        if (!(connections instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(this::safeConnection)
                .toList();
    }

    private Map<String, Object> safeConnection(Map<?, ?> connection) {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("active", Boolean.TRUE.equals(connection.get("active")));
        safe.put("endpointId", connection.get("endpointId"));
        return safe;
    }

    private Map<String, Object> safeSessionStats(Map<String, Object> stats) {
        if (stats == null || stats.isEmpty()) {
            return Map.of("activeConnections", 0, "workerCount", 0L);
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("activeConnections", stats.getOrDefault("activeConnections", 0));
        safe.put("workerCount", stats.getOrDefault("workerCount", 0L));
        return safe;
    }
}
