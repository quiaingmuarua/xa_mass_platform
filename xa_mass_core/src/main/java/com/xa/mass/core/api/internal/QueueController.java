package com.xa.mass.core.api.internal;

import com.xa.mass.core.getway.queue.Envelope;
import com.xa.mass.core.getway.queue.MessageQueue;
import io.swagger.annotations.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
@RequestMapping("/api/queue")
@Api(tags = "队列监控")
public class QueueController {

    @Autowired
    @Qualifier("inputQueue")
    private MessageQueue<Envelope> inputQueue;

    @Autowired
    @Qualifier("outputQueue")
    private MessageQueue<Envelope> outputQueue;

    @GetMapping("/status")
    @ApiOperation("获取 input/output 队列状态")
    public Map<String, Object> getQueueStatus() {
        Map<String, Object> map = new HashMap<>();
        map.put("inputQueue", inputQueue.size());
        map.put("outputQueue", outputQueue.size());
        return success(map);
    }

    @GetMapping("/detail")
    @ApiOperation("获取队列明细（预留，后续可扩展）")
    public Map<String, Object> getQueueDetail() {
        // 这里只返回空列表，后续可扩展为返回队列内消息明细
        Map<String, Object> map = new HashMap<>();
        map.put("inputQueueDetail", Collections.emptyList());
        map.put("outputQueueDetail", Collections.emptyList());
        return success(map);
    }

    @GetMapping("/metrics")
    @ApiOperation("获取队列速率统计（预留，后续可扩展）")
    public Map<String, Object> getQueueMetrics() {
        // 这里只返回静态数据，后续可扩展为动态统计
        Map<String, Object> map = new HashMap<>();
        map.put("inputQueueRate", 0);
        map.put("outputQueueRate", 0);
        return success(map);
    }

    private Map<String, Object> success(Object data) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", 0);
        resp.put("msg", "ok");
        resp.put("data", data);
        return resp;
    }
} 