package com.xa.mass.core.model.message.enums;

public enum MessageType {
    TASK,         // 下发任务
    PING,         // 心跳
    PONG,         // 心跳响应
    STATUS,       // 状态上报，如执行中、在线等
    CONTROL,      // 控制类消息，如暂停、取消、断开等
    LOG,          // 日志上传
    EVENT,        // 事件通知，如登录成功、意外中断等
    CONFIG,       // 配置更新（如更新 client 配置）
    REGISTER      // 首次连接注册、client 连接信息交换
}
