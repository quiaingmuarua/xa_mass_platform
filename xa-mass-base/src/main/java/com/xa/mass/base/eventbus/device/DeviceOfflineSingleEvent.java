package com.xa.mass.base.eventbus.device;

import com.xa.mass.base.eventbus.core.MassEvent;
import com.xa.mass.base.eventbus.core.MassPlatformEventType;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 设备单个下线事件
 */
public class DeviceOfflineSingleEvent extends MassEvent.BaseMassEvent {
    private final String deviceId;
    private final String reason;
    private final long offlineDurationMs;

    public DeviceOfflineSingleEvent(String deviceId, String reason, long offlineDurationMs, String traceId) {
        super(
                "DEVICE_OFFLINE_SINGLE",
                MassPlatformEventType.DEVICE_OFFLINE_SINGLE,
                String.format("设备 %s 下线，原因: %s，下线时长: %dms", deviceId, reason, offlineDurationMs),
                createMetadata(deviceId, reason, offlineDurationMs),
                traceId,
                null
        );
        this.deviceId = deviceId;
        this.reason = reason;
        this.offlineDurationMs = offlineDurationMs;
    }

    private static Map<String, Object> createMetadata(String deviceId, String reason, long offlineDurationMs) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("deviceId", deviceId);
        metadata.put("reason", reason);
        metadata.put("offlineDurationMs", offlineDurationMs);
        return Collections.unmodifiableMap(metadata);
    }

    public String getDeviceId() { return deviceId; }
    public String getReason() { return reason; }
    public long getOfflineDurationMs() { return offlineDurationMs; }
} 