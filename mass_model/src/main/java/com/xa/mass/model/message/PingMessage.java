package com.xa.mass.model.message;


public class PingMessage {
    private String deviceId;
    private long timestamp;

    public PingMessage() {}

    public PingMessage(String deviceId, long timestamp) {
        this.deviceId = deviceId;
        this.timestamp = timestamp;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}