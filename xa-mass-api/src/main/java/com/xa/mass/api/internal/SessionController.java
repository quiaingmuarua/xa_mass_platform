package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.gateway.dispatcher.DispatcherContextRegistry;
import com.xa.mass.gateway.dispatcher.context.SessionContext;
import com.xa.mass.gateway.session.ServerSessionManager;
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
        ServerSessionManager sessionManager = resolveSessionManager();
        if (sessionManager != null) {
            sessionManager.getAllWorkerChannels().forEach((workerId, roleMap) -> {
                Map<String, Object> entry = new HashMap<>();
                entry.put("workerId", workerId);
                List<Map<String, Object>> roles = new ArrayList<>();
                roleMap.forEach((role, channel) -> {
                    Map<String, Object> roleInfo = new HashMap<>();
                    roleInfo.put("role", role);
                    roleInfo.put("active", channel.isActive());
                    roleInfo.put("channelId", channel.id().asShortText());
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
        ServerSessionManager sessionManager = resolveSessionManager();
        if (sessionManager != null) {
            data.put("activeConnections", sessionManager.getWorkerConnectionCount());
            data.put("workerCount", sessionManager.getAllWorkerChannels().size());
        } else {
            data.put("activeConnections", 0);
            data.put("workerCount", 0);
        }
        return ApiResponse.success(data);
    }

    private ServerSessionManager resolveSessionManager() {
        SessionContext ctx = DispatcherContextRegistry.getSessionContext();
        return ctx != null ? ctx.getSessionManager() : null;
    }
}
