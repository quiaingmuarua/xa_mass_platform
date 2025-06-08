package com.xa.mass.model;

import lombok.Getter;

import java.io.Serializable;


public class Device implements Serializable {

    private String deviceId;        // 设备唯一标识
    private String groupId;         // 所属分组
    private String clientVersion;   // 客户端版本
    private int deviceState;        // -1未知 1在线 2任务中 3掉线


    public Device() {
    }

    public Device(String deviceId, String groupId, String clientVersion, int deviceState) {
        this.deviceId = deviceId;
        this.groupId = groupId;
        this.clientVersion = clientVersion;
        this.deviceState = deviceState;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public void setClientVersion(String clientVersion) {
        this.clientVersion = clientVersion;
    }

    public void setDeviceState(int deviceState) {
        this.deviceState = deviceState;
    }

    @Override
    public String toString() {
        return "Device{" +
                "deviceId='" + deviceId + '\'' +
                ", groupId='" + groupId + '\'' +
                ", clientVersion='" + clientVersion + '\'' +
                ", deviceState=" + deviceState +
                '}';
    }
}