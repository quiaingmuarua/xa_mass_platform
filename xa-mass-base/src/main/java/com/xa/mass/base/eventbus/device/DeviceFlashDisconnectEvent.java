package com.xa.mass.base.eventbus.device;

import com.xa.mass.base.eventbus.core.MassEvent;
import com.xa.mass.base.eventbus.core.MassPlatformEventType;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 设备闪断事件
 */
public class DeviceFlashDisconnectEvent extends MassEvent.BaseMassEvent {
    private final String deviceId;
    private final int disconnectCount;
    private final long disconnectIntervalMs;

    public DeviceFlashDisconnectEvent(String deviceId, int disconnectCount, long disconnectIntervalMs, String traceId) {
        super(
                "DEVICE_FLASH_DISCONNECT",
                MassPlatformEventType.DEVICE_FLASH_DISCONNECT,
                String.format("设备 %s 闪断，断开次数: %d，间隔: %dms", deviceId, disconnectCount, disconnectIntervalMs),
                createMetadata(deviceId, disconnectCount, disconnectIntervalMs),
                traceId,
                null
        );
        this.deviceId = deviceId;
        this.disconnectCount = disconnectCount;
        this.disconnectIntervalMs = disconnectIntervalMs;
    }

    private static Map<String, Object> createMetadata(String deviceId, int disconnectCount, long disconnectIntervalMs) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("deviceId", deviceId);
        metadata.put("disconnectCount", disconnectCount);
        metadata.put("disconnectIntervalMs", disconnectIntervalMs);
        return Collections.unmodifiableMap(metadata);
    }

    public String getDeviceId() { return deviceId; }
    public int getDisconnectCount() { return disconnectCount; }
    public long getDisconnectIntervalMs() { return disconnectIntervalMs; }
} 