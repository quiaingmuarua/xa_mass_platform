package com.xa.mass.base.eventbus.device;

import com.xa.mass.base.eventbus.core.MassEvent;
import com.xa.mass.base.eventbus.core.MassPlatformEventType;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 设备长时间不归队事件
 */
public class DeviceLongAbsenceEvent extends MassEvent.BaseMassEvent {
    private final String deviceId;
    private final long absenceDurationMs;
    private final String lastSeenTime;

    public DeviceLongAbsenceEvent(String deviceId, long absenceDurationMs, String lastSeenTime, String traceId) {
        super(
                "DEVICE_LONG_ABSENCE",
                MassPlatformEventType.DEVICE_LONG_ABSENCE,
                String.format("设备 %s 长时间不归队，缺席时长: %dms，最后在线: %s", deviceId, absenceDurationMs, lastSeenTime),
                createMetadata(deviceId, absenceDurationMs, lastSeenTime),
                traceId,
                null
        );
        this.deviceId = deviceId;
        this.absenceDurationMs = absenceDurationMs;
        this.lastSeenTime = lastSeenTime;
    }

    private static Map<String, Object> createMetadata(String deviceId, long absenceDurationMs, String lastSeenTime) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("deviceId", deviceId);
        metadata.put("absenceDurationMs", absenceDurationMs);
        metadata.put("lastSeenTime", lastSeenTime);
        return Collections.unmodifiableMap(metadata);
    }

    public String getDeviceId() { return deviceId; }
    public long getAbsenceDurationMs() { return absenceDurationMs; }
    public String getLastSeenTime() { return lastSeenTime; }
} 