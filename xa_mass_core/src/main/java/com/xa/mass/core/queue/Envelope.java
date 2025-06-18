package com.xa.mass.core.queue;


import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

@Data
@Builder
public class Envelope {
    private String rawJson;     // 原始 JSON 消息
    private String deviceId;
    private String connRole;
    private String traceId;     // 可选，用于日志追踪
    private long receivedAt;    // 接收时间戳
}
