package com.xa.mass.core.api.internal;

import io.swagger.annotations.*;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/debug")
@Api(tags = "调试接口")
public class DebugController {

    @PostMapping("/sendRaw")
    @ApiOperation("原始 Envelope 调用，便于调试")
    public Map<String, Object> sendRaw(@RequestBody Map<String, Object> req) {
        // TODO: 实现实际调试逻辑
        // 参数示例: {"rawJson": "{...}"}
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