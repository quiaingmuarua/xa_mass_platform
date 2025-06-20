package com.xa.mass.core.api.internal;

import io.swagger.annotations.*;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;
import com.xa.mass.core.getway.dispatcher.DispatcherContextRegistry;
import com.xa.mass.core.getway.dispatcher.context.TransportContext;
import com.xa.mass.core.getway.queue.Envelope;
import com.xa.mass.core.getway.queue.MessageTransporter;

@RestController
@RequestMapping("/api/message")
@Api(tags = "消息推送")
public class MessageController {

    @PostMapping("/send")
    @ApiOperation("主动推送消息到指定 device/role")
    public Map<String, Object> sendMessage(@RequestBody Map<String, Object> req) {
        // 示例参数: {"deviceId": "dev123", "role": "USER", "content": "hello"}
        boolean successFlag = false;
        String msg = "";
        TransportContext transportContext = DispatcherContextRegistry.getTransportContext();
        if (transportContext != null) {
            MessageTransporter messageTransporter = transportContext.getMessageTransporter();
            if (messageTransporter != null) {
                // 这里只做简单演示，实际应构造 Envelope
                String rawJson = req.toString();
                Envelope env = Envelope.builder().rawJson(rawJson).receivedAt(System.currentTimeMillis()).build();
                messageTransporter.sendOutput(env);
                successFlag = true;
                msg = "消息已入队";
            } else {
                msg = "MessageTransporter 未初始化";
            }
        } else {
            msg = "TransportContext 未初始化";
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", successFlag);
        result.put("msg", msg);
        return success(result);
    }

    private Map<String, Object> success(Object data) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", 0);
        resp.put("msg", "ok");
        resp.put("data", data);
        return resp;
    }
} 