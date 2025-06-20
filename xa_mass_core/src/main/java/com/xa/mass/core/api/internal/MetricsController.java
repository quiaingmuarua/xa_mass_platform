package com.xa.mass.core.api.internal;

import io.swagger.annotations.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.HashMap;
import java.util.Map;
import com.xa.mass.core.api.model.ApiResponse;
import com.xa.mass.core.getway.dispatcher.DispatcherContextRegistry;
import com.xa.mass.core.getway.queue.MessageTransporter;

@RestController
@RequestMapping("/api/metrics")
@Api(tags = "消息速率/统计")
public class MetricsController {

    @GetMapping("")
    @ApiOperation("获取消息速率/统计信息")
    public ApiResponse<Map<String, Object>> getMetrics() {
        Map<String, Object> data = new HashMap<>();
        if (DispatcherContextRegistry.get() != null) {
            MessageTransporter messageTransporter = DispatcherContextRegistry.get().getMessageTransporter();
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