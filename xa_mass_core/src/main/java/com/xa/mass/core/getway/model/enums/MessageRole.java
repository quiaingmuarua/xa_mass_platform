package com.xa.mass.core.getway.model.enums;

public enum MessageRole {
    ORIGIN,     // 原始请求消息
    RESPONSE,   // 响应请求消息
    ACK,        // 简单确认（比如收到 TASK）
    NOTIFY,     // 主动通知（服务推送）
    BROADCAST,   // 广播型消息
    RESPONSE_STEP  // 单步的响应
}
