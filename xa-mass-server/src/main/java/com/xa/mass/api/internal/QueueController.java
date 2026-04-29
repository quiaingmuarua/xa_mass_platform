package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.sdk.TransportOperations;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/queue")
@Tag(name = "Queue Status")
public class QueueController {

    private static final Logger log = LoggerFactory.getLogger(QueueController.class);
    private final TransportOperations transportOperations;

    public QueueController(TransportOperations transportOperations) {
        this.transportOperations = transportOperations;
    }

    @GetMapping("/status")
    @Operation(summary = "Get the current input/output queue sizes")
    public ApiResponse<Map<String, Object>> getQueueStatus() {
        log.info("[QueueController] /api/queue/status requested");
        Map<String, Object> detail = transportOperations.getQueueDetail();
        Object inputSize = detail.getOrDefault("inputQueueSize", -1);
        Object outputSize = detail.getOrDefault("outputQueueSize", -1);
        Map<String, Object> map = Map.of(
                "inputQueueSize", inputSize,
                "outputQueueSize", outputSize
        );
        log.info("[QueueController] inputQueueSize={}, outputQueueSize={}", inputSize, outputSize);
        return ApiResponse.success(map);
    }

    @GetMapping("/detail")
    @Operation(summary = "Get detailed queue availability data")
    public ApiResponse<Map<String, Object>> getQueueDetail() {
        return ApiResponse.success(transportOperations.getQueueDetail());
    }

    @GetMapping("/metrics")
    @Operation(summary = "Get reserved queue metrics")
    public ApiResponse<Map<String, Object>> getQueueMetrics() {
        return ApiResponse.success(transportOperations.getQueueMetrics());
    }
}
