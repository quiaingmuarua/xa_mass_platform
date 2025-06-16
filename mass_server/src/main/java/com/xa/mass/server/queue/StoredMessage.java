package com.xa.mass.server.queue;


import java.io.Serializable;

public class StoredMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    private String messageContent; // WebSocket 文本消息 (JSON 字符串)
    private String deviceId;       // 目标设备的 ID
    private String connRole;       // 目标设备的连接角色

    // Jackson/Gson 等序列化库需要无参构造函数
    public StoredMessage() {
    }

    public StoredMessage(String messageContent, String deviceId, String connRole) {
        this.messageContent = messageContent;
        this.deviceId = deviceId;
        this.connRole = connRole;
    }

    // Getters and Setters
    public String getMessageContent() {
        return messageContent;
    }

    public void setMessageContent(String messageContent) {
        this.messageContent = messageContent;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getConnRole() {
        return connRole;
    }

    public void setConnRole(String connRole) {
        this.connRole = connRole;
    }

    @Override
    public String toString() {
        return "StoredMessage{" +
                "messageContent='" + (messageContent != null && messageContent.length() > 50 ? messageContent.substring(0, 50) + "..." : messageContent) + '\'' +
                ", deviceId='" + deviceId + '\'' +
                ", connRole='" + connRole + '\'' +
                '}';
    }
}