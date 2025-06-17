package com.xa.mass.core.server.manager;

import java.util.Objects;

/**
 * 表示一个设备连接的唯一标识：deviceId + connRole
 */
public class DeviceConnKey {
    private final String deviceId;
    private final String connRole;

    public DeviceConnKey(String deviceId, String connRole) {
        this.deviceId = deviceId;
        this.connRole = connRole;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getConnRole() {
        return connRole;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeviceConnKey)) return false;
        DeviceConnKey that = (DeviceConnKey) o;
        return Objects.equals(deviceId, that.deviceId) &&
                Objects.equals(connRole, that.connRole);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deviceId, connRole);
    }

    @Override
    public String toString() {
        return "DeviceConnKey{" +
                "deviceId='" + deviceId + '\'' +
                ", connRole='" + connRole + '\'' +
                '}';
    }
}
