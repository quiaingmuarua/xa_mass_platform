package com.xa.mass.core.api.internal;

import io.swagger.annotations.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import com.xa.mass.core.getway.dispatcher.DispatcherContextRegistry;
import com.xa.mass.core.getway.session.ServerSessionManager;
import com.xa.mass.core.api.model.ApiResponse;

@RestController
@RequestMapping("/api/session")
@Api(tags = "Session管理")
public class SessionController {

    @GetMapping("/list")
    @ApiOperation("获取所有在线Session/Device详情")
    public ApiResponse<List<Map<String, Object>>> listSessions() {
        List<Map<String, Object>> data = new ArrayList<>();
        if (DispatcherContextRegistry.get() != null) {
            ServerSessionManager sessionManager = DispatcherContextRegistry.get().getSessionManager();
            if (sessionManager != null) {
                Map<String, Object> info = new HashMap<>();
                info.put("sessionManager", sessionManager.toString());
                data.add(info);
            }
        }
        return ApiResponse.success(data);
    }

    @GetMapping("/stats")
    @ApiOperation("连接统计")
    public ApiResponse<Map<String, Object>> sessionStats() {
        Map<String, Object> data = new HashMap<>();
        if (DispatcherContextRegistry.get() != null) {
            ServerSessionManager sessionManager = DispatcherContextRegistry.get().getSessionManager();
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