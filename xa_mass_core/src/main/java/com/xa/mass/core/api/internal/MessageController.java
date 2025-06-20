package com.xa.mass.core.api.internal;

import io.swagger.annotations.*;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/message")
@Api(tags = "消息推送")
public class MessageController {

    @PostMapping("/send")
    @ApiOperation("主动推送消息到指定 device/role")
    public Map<String, Object> sendMessage(@RequestBody Map<String, Object> req) {
        // TODO: 实现实际推送逻辑
        // 参数示例: {"deviceId": "dev123", "role": "USER", "content": "hello"}
        return success(null);
    }

    private Map<String, Object> success(Object data) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", 0);
        resp.put("msg", "ok");
        resp.put("data", data);
        return resp;
    }
} 