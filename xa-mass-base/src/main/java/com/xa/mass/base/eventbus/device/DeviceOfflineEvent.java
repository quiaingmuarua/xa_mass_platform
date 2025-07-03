package com.xa.mass.base.eventbus.device;

import com.xa.mass.base.eventbus.core.MassEvent;
import com.xa.mass.base.eventbus.core.MassPlatformEventType;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 设备下线事件（单设备）
 */
public class DeviceOfflineEvent extends MassEvent.BaseMassEvent {
    private final String deviceId;
    private final String reason;

    public DeviceOfflineEvent(String deviceId, String reason, String traceId) {
        super(
                "DEVICE_OFFLINE",
                MassPlatformEventType.DEVICE_OFFLINE_SINGLE, // 可新建 DEVICE_OFFLINE 单独枚举
                String.format("设备 %s 下线，原因: %s", deviceId, reason),
                createMetadata(deviceId, reason),
                traceId,
                null
        );
        this.deviceId = deviceId;
        this.reason = reason;
    }

    private static Map<String, Object> createMetadata(String deviceId, String reason) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("deviceId", deviceId);
        metadata.put("reason", reason);
        return Collections.unmodifiableMap(metadata);
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getReason() {
        return reason;
    }
} 