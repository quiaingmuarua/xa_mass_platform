package com.xa.mass.api.internal;

import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.HashMap;
import java.util.Map;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.gateway.dispatcher.DispatcherContextRegistry;
import com.xa.mass.gateway.dispatcher.context.TransportContext;
import com.xa.mass.gateway.queue.MessageTransporter;

@RestController
@RequestMapping("/api/metrics")
@Tag(name = "消息速率/统计")
public class MetricsController {

    @GetMapping("")
    @Operation(summary = "获取消息速率/统计信息")
    public ApiResponse<Map<String, Object>> getMetrics() {
        Map<String, Object> data = new HashMap<>();
        TransportContext transportContext = DispatcherContextRegistry.getTransportContext();
        if (transportContext != null) {
            MessageTransporter messageTransporter = transportContext.getMessageTransporter();
            if (messageTransporter != null) {
                data.put("inputQueueSize", messageTransporter.inputQueueSize());
                data.put("outputQueueSize", messageTransporter.outputQueueSize());
            } else {
                data.put("inputQueueSize", -1);
                data.put("outputQueueSize", -1);
            }
        }
        // 其他统计数据可后续扩展
        return ApiResponse.success(data);
    }
} 