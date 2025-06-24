package com.xa.mass.api.internal;

import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.HashMap;
import java.util.Map;
import com.xa.mass.api.model.ApiResponse;

@RestController
@RequestMapping("/api/health")
@Tag(name = "健康检查")
public class HealthController {

    @GetMapping("")
    @Operation(summary = "获取网关节点健康状态")
    public ApiResponse<Map<String, Object>> getHealth() {
        Map<String, Object> data = new HashMap<>();
        data.put("status", "UP");
        data.put("timestamp", System.currentTimeMillis());
        return ApiResponse.success(data);
    }
} 