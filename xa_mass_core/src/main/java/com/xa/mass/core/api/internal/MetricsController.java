package com.xa.mass.core.api.internal;

import io.swagger.annotations.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/metrics")
@Api(tags = "消息速率/统计")
public class MetricsController {

    @GetMapping("")
    @ApiOperation("获取消息速率/统计信息")
    public Map<String, Object> getMetrics() {
        // TODO: 后续可接入真实统计数据
        Map<String, Object> data = new HashMap<>();
        data.put("msgPerMin", 1200);
        data.put("msgPer5Min", 6000);
        data.put("avgProcessTimeMs", 8.5);
        return success(data);
    }

    private Map<String, Object> success(Object data) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", 0);
        resp.put("msg", "ok");
        resp.put("data", data);
        return resp;
    }
} 