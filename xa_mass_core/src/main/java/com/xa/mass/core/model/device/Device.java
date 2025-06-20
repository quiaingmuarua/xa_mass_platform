package com.xa.mass.core.model.device;

import java.io.Serializable;

public class Device implements Serializable {
    private String deviceId;        // 设备唯一标识
    private String groupId;         // 所属分组
    private String clientVersion;   // 客户端版本
    private int deviceState;        // -1未知 1在线 2任务中 3掉线

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getClientVersion() {
        return clientVersion;
    }

    public void setClientVersion(String clientVersion) {
        this.clientVersion = clientVersion;
    }

    public int getDeviceState() {
        return deviceState;
    }

    public void setDeviceState(int deviceState) {
        this.deviceState = deviceState;
    }
}
