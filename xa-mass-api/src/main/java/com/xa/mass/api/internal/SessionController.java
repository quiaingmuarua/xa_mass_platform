package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.gateway.dispatcher.DispatcherContextRegistry;
import com.xa.mass.gateway.dispatcher.context.SessionContext;
import com.xa.mass.gateway.session.ServerSessionManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/session")
@Tag(name = "Session管理")
public class SessionController {

    @GetMapping("/list")
    @Operation(summary = "获取所有在线Session/Device详情")
    public ApiResponse<List<Map<String, Object>>> listSessions() {
        List<Map<String, Object>> data = new ArrayList<>();
        SessionContext sessionContext = DispatcherContextRegistry.getSessionContext();
        if (sessionContext != null) {
            ServerSessionManager sessionManager = sessionContext.getSessionManager();
            if (sessionManager != null) {
                Map<String, Object> info = new HashMap<>();
                info.put("sessionManager", sessionManager.toString());
                data.add(info);
            }
        }
        return ApiResponse.success(data);
    }

    @GetMapping("/stats")
    @Operation(summary = "连接统计")
    public ApiResponse<Map<String, Object>> sessionStats() {
        Map<String, Object> data = new HashMap<>();
        SessionContext sessionContext = DispatcherContextRegistry.getSessionContext();
        if (sessionContext != null) {
            ServerSessionManager sessionManager = sessionContext.getSessionManager();
            if (sessionManager != null) {
                data.put("sessionManager", sessionManager.toString());
            } else {
                data.put("sessionManager", "null");
            }
        } else {
            data.put("sessionManager", "null");
        }
        return ApiResponse.success(data);
    }
} 