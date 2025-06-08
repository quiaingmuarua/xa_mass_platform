package com.xa.mass.model.message;


public class PongMessage {
    private String deviceId;
    private long timestamp;

    public PongMessage() {}

    public PongMessage(String deviceId, long timestamp) {
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