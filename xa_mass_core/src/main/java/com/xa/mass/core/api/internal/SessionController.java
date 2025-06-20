package com.xa.mass.core.api.internal;

import io.swagger.annotations.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
@RequestMapping("/api/session")
@Api(tags = "Session管理")
public class SessionController {

    @GetMapping("/list")
    @ApiOperation("获取所有在线Session/Device详情")
    public Map<String, Object> listSessions() {
        // TODO: 后续可从 SessionManager 获取真实数据
        List<Map<String, Object>> data = new ArrayList<>();
        return success(data);
    }

    @GetMapping("/stats")
    @ApiOperation("连接统计")
    public Map<String, Object> sessionStats() {
        // TODO: 后续可从 SessionManager 获取真实数据
        Map<String, Object> data = new HashMap<>();
        data.put("total", 0);
        data.put("online", 0);
        data.put("offline", 0);
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