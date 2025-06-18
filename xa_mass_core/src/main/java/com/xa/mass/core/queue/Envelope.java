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
    private long receivedAt;


    @Override
    public String toString() {
        return "Envelope{" +
                "deviceId='" + deviceId + '\'' +
                ", connRole='" + connRole + '\'' +
                ", traceId='" + traceId + '\'' +
                ", receivedAt=" + receivedAt +
                ", rawJson=" + (rawJson != null ? rawJson.substring(0, Math.min(100, rawJson.length())) + "..." : null) +
                '}';
    }
// 接收时间戳
}
