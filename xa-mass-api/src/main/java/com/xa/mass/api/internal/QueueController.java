package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.gateway.dispatcher.DispatcherContextRegistry;
import com.xa.mass.gateway.dispatcher.context.TransportContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/queue")
@Tag(name = "Queue Status")
public class QueueController {

    private static final Logger log = LoggerFactory.getLogger(QueueController.class);

    @GetMapping("/status")
    @Operation(summary = "Get the current input/output queue sizes")
    public ApiResponse<Map<String, Object>> getQueueStatus() {
        log.info("[QueueController] /api/queue/status requested");
        Map<String, Object> map = new HashMap<>();
        TransportContext transportContext = DispatcherContextRegistry.getTransportContext();
        MessageTransporter messageTransporter = transportContext != null ? transportContext.getMessageTransporter() : null;
        int inputSize = -1;
        int outputSize = -1;

        try {
            inputSize = messageTransporter != null ? messageTransporter.inputQueueSize() : -1;
        } catch (Exception e) {
            log.error("Failed to read input queue size", e);
        }
        try {
            outputSize = messageTransporter != null ? messageTransporter.outputQueueSize() : -1;
        } catch (Exception e) {
            log.error("Failed to read output queue size", e);
        }

        map.put("inputQueue", inputSize);
        map.put("outputQueue", outputSize);
        log.info("[QueueController] inputQueue={}, outputQueue={}", inputSize, outputSize);
        return ApiResponse.success(map);
    }

    @GetMapping("/detail")
    @Operation(summary = "Get detailed queue availability data")
    public ApiResponse<Map<String, Object>> getQueueDetail() {
        Map<String, Object> map = new HashMap<>();
        TransportContext transportContext = DispatcherContextRegistry.getTransportContext();
        MessageTransporter messageTransporter = transportContext != null ? transportContext.getMessageTransporter() : null;
        int inputSize = messageTransporter != null ? messageTransporter.inputQueueSize() : -1;
        int outputSize = messageTransporter != null ? messageTransporter.outputQueueSize() : -1;
        map.put("inputQueueSize", inputSize);
        map.put("outputQueueSize", outputSize);
        map.put("transporterAvailable", messageTransporter != null);
        return ApiResponse.success(map);
    }

    @GetMapping("/metrics")
    @Operation(summary = "Get reserved queue metrics")
    public ApiResponse<Map<String, Object>> getQueueMetrics() {
        Map<String, Object> map = new HashMap<>();
        map.put("inputQueueRate", 0);
        map.put("outputQueueRate", 0);
        return ApiResponse.success(map);
    }
}
