package com.xa.mass.core.api.internal;

import io.swagger.annotations.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.HashMap;
import java.util.Map;
import com.xa.mass.core.api.model.ApiResponse;

@RestController
@RequestMapping("/api/health")
@Api(tags = "健康检查")
public class HealthController {

    @GetMapping("")
    @ApiOperation("获取网关节点健康状态")
    public ApiResponse<Map<String, Object>> getHealth() {
        Map<String, Object> data = new HashMap<>();
        data.put("status", "UP");
        data.put("timestamp", System.currentTimeMillis());
        return ApiResponse.success(data);
    }
} 