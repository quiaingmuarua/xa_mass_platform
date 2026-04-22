package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.gateway.dispatcher.DispatcherContextRegistry;
import com.xa.mass.gateway.dispatcher.context.SessionContext;
import com.xa.mass.transport.WorkerEndpointInspector;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.WorkerEndpointSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/session")
@Tag(name = "Session Status")
public class SessionController {

    @GetMapping("/list")
    @Operation(summary = "List all active worker sessions")
    public ApiResponse<List<Map<String, Object>>> listSessions() {
        List<Map<String, Object>> data = new ArrayList<>();
        WorkerEndpointInspector sessionInspector = resolveSessionInspector();
        if (sessionInspector != null) {
            Map<String, List<WorkerEndpointSnapshot>> grouped = new HashMap<>();
            for (WorkerEndpointSnapshot snapshot : sessionInspector.listWorkerEndpoints()) {
                grouped.computeIfAbsent(snapshot.getWorkerId(), ignored -> new ArrayList<>()).add(snapshot);
            }
            grouped.forEach((workerId, endpoints) -> {
                Map<String, Object> entry = new HashMap<>();
                entry.put("workerId", workerId);
                List<Map<String, Object>> roles = new ArrayList<>();
                endpoints.forEach(snapshot -> {
                    Map<String, Object> roleInfo = new HashMap<>();
                    roleInfo.put("role", snapshot.getEndpointRole());
                    roleInfo.put("active", snapshot.isActive());
                    roleInfo.put("endpointId", snapshot.getEndpointId());
                    roleInfo.put("transport", snapshot.getTransport());
                    roles.add(roleInfo);
                });
                entry.put("connections", roles);
                data.add(entry);
            });
        }
        return ApiResponse.success(data);
    }

    @GetMapping("/stats")
    @Operation(summary = "Get aggregate session statistics")
    public ApiResponse<Map<String, Object>> sessionStats() {
        Map<String, Object> data = new HashMap<>();
        WorkerEndpointRegistry sessionManager = resolveSessionManager();
        WorkerEndpointInspector sessionInspector = resolveSessionInspector();
        if (sessionManager != null) {
            data.put("activeConnections", sessionManager.getActiveConnectionCount());
            data.put("workerCount", sessionInspector != null
                    ? sessionInspector.listWorkerEndpoints().stream().map(WorkerEndpointSnapshot::getWorkerId).distinct().count()
                    : 0);
        } else {
            data.put("activeConnections", 0);
            data.put("workerCount", 0);
        }
        return ApiResponse.success(data);
    }

    private WorkerEndpointRegistry resolveSessionManager() {
        SessionContext ctx = DispatcherContextRegistry.getSessionContext();
        if (ctx == null || ctx.getSessionManager() == null) {
            return null;
        }
        return ctx.getSessionManager();
    }

    private WorkerEndpointInspector resolveSessionInspector() {
        WorkerEndpointRegistry sessionManager = resolveSessionManager();
        return sessionManager instanceof WorkerEndpointInspector inspector ? inspector : null;
    }
}
