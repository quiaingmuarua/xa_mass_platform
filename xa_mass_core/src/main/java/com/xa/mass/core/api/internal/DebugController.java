package com.xa.mass.core.api.internal;

import io.swagger.annotations.*;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;
import com.xa.mass.core.getway.dispatcher.DispatcherContextRegistry;
import com.xa.mass.core.getway.queue.Envelope;
import com.xa.mass.core.getway.queue.MessageTransporter;
import com.xa.mass.core.api.model.ApiResponse;

@RestController
@RequestMapping("/api/debug")
@Api(tags = "调试接口")
public class DebugController {

    @PostMapping("/sendRaw")
    @ApiOperation("原始 Envelope 调用，便于调试")
    public ApiResponse<Map<String, Object>> sendRaw(@RequestBody Map<String, Object> req) {
        boolean successFlag = false;
        String msg = "";
        if (DispatcherContextRegistry.get() != null) {
            MessageTransporter messageTransporter = DispatcherContextRegistry.get().getMessageTransporter();
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
            msg = "DispatcherContext 未初始化";
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", successFlag);
        result.put("msg", msg);
        return ApiResponse.success(result);
    }
} 