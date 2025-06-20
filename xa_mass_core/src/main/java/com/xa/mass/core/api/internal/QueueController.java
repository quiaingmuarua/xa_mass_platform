package com.xa.mass.core.api.internal;

import com.xa.mass.core.api.model.ApiResponse;
import com.xa.mass.core.getway.queue.Envelope;
import com.xa.mass.core.getway.queue.MessageTransporter;
import com.xa.mass.core.getway.dispatcher.DispatcherContextRegistry;
import com.xa.mass.core.getway.dispatcher.context.TransportContext;
import io.swagger.annotations.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
@RequestMapping("/api/queue")
@Api(tags = "队列监控")
public class QueueController {

    private static final Logger log = LoggerFactory.getLogger(QueueController.class);

    @GetMapping("/status")
    @ApiOperation("获取 input/output 队列状态")
    public com.xa.mass.core.api.model.ApiResponse<Map<String, Object>> getQueueStatus() {
        log.info("[QueueController] /api/queue/status 请求到达");
        Map<String, Object> map = new HashMap<>();
        TransportContext transportContext = DispatcherContextRegistry.getTransportContext();
        MessageTransporter messageTransporter = transportContext != null ? transportContext.getMessageTransporter() : null;
        log.info("[QueueController] messageTransporter: {}", messageTransporter);
        int inputSize = -1;
        int outputSize = -1;
        try {
            inputSize = messageTransporter != null ? messageTransporter.inputQueueSize() : -1;
        } catch (Exception e) {
            log.error("获取 inputQueue.size() 异常", e);
        }
        try {
            outputSize = messageTransporter != null ? messageTransporter.outputQueueSize() : -1;
        } catch (Exception e) {
            log.error("获取 outputQueue.size() 异常", e);
        }
        map.put("inputQueue", inputSize);
        map.put("outputQueue", outputSize);
        log.info("[QueueController] inputQueue size: {}, outputQueue size: {}", inputSize, outputSize);
        return com.xa.mass.core.api.model.ApiResponse.success(map);
    }

    @GetMapping("/detail")
    @ApiOperation("获取队列明细（预留，后续可扩展）")
    public com.xa.mass.core.api.model.ApiResponse<Map<String, Object>> getQueueDetail() {
        Map<String, Object> map = new HashMap<>();
        TransportContext transportContext = DispatcherContextRegistry.getTransportContext();
        MessageTransporter messageTransporter = transportContext != null ? transportContext.getMessageTransporter() : null;
        map.put("inputQueueDetail", messageTransporter != null ? messageTransporter.toString() : Collections.emptyList());
        map.put("outputQueueDetail", messageTransporter != null ? messageTransporter.toString() : Collections.emptyList());
        return com.xa.mass.core.api.model.ApiResponse.success(map);
    }

    @GetMapping("/metrics")
    @ApiOperation("获取队列速率统计（预留，后续可扩展）")
    public com.xa.mass.core.api.model.ApiResponse<Map<String, Object>> getQueueMetrics() {
        Map<String, Object> map = new HashMap<>();
        // 这里只返回静态数据，后续可扩展为动态统计
        map.put("inputQueueRate", 0);
        map.put("outputQueueRate", 0);
        return ApiResponse.success(map);
    }
} 