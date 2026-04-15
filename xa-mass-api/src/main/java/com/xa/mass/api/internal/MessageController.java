package com.xa.mass.api.internal;

import com.google.gson.Gson;
import com.xa.mass.gateway.dispatcher.DispatcherContextRegistry;
import com.xa.mass.gateway.dispatcher.context.TransportContext;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.base.channel.tranporter.MessageTransporter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/message")
@Tag(name = "消息推送")
public class MessageController {

    private static final Gson GSON = new Gson();

    @PostMapping("/send")
    @Operation(summary = "主动推送消息到指定 worker/role")
    public Map<String, Object> sendMessage(@RequestBody Map<String, Object> req) {
        // 示例参数: {"workerId": "worker-123", "role": "USER", "content": "hello"}
        boolean successFlag = false;
        String msg = "";
        TransportContext transportContext = DispatcherContextRegistry.getTransportContext();
        if (transportContext != null) {
            MessageTransporter messageTransporter = transportContext.getMessageTransporter();
            if (messageTransporter != null) {
                String rawJson = GSON.toJson(req);
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