package com.xa.mass.base.channel.eventbus.event.device;

import com.xa.mass.base.channel.eventbus.core.MassEvent;
import com.xa.mass.base.channel.eventbus.core.MassPlatformEventType;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class DeviceOfflineEvent extends MassEvent.BaseMassEvent {
    private final String deviceId;
    private final String reason;

    public DeviceOfflineEvent(String deviceId, String reason, String traceId) {
        super(
                "DEVICE_OFFLINE",
                MassPlatformEventType.DEVICE_OFFLINE_SINGLE,
                String.format("Device %s is offline: %s", deviceId, reason),
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
