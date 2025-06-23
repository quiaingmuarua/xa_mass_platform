package com.xa.mass.api.internal;

import io.swagger.annotations.*;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;
import com.xa.mass.gateway.dispatcher.DispatcherContextRegistry;
import com.xa.mass.gateway.dispatcher.context.TransportContext;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.gateway.queue.MessageTransporter;
import com.xa.mass.api.model.ApiResponse;

@RestController
@RequestMapping("/api/debug")
@Api(tags = "调试接口")
public class DebugController {

    @PostMapping("/sendRaw")
    @ApiOperation("原始 Envelope 调用，便于调试")
    public ApiResponse<Map<String, Object>> sendRaw(@RequestBody Map<String, Object> req) {
        boolean successFlag = false;
        String msg = "";
        TransportContext transportContext = DispatcherContextRegistry.getTransportContext();
        if (transportContext != null) {
            MessageTransporter messageTransporter = transportContext.getMessageTransporter();
            if (messageTransporter != null) {
                String rawJson = req.toString();
                Envelope env = Envelope.builder().rawJson(rawJson).receivedAt(System.currentTimeMillis()).build();
                messageTransporter.sendInput(env);
                successFlag = true;
                msg = "Envelope 已入 inputQueue";
            } else {
                msg = "MessageTransporter 未初始化";
            }
        } else {
            msg = "TransportContext 未初始化";
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", successFlag);
        result.put("msg", msg);
        return ApiResponse.success(result);
    }
} 