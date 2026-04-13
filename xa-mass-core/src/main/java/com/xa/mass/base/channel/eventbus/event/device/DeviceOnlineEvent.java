package com.xa.mass.base.channel.eventbus.event.device;

import com.xa.mass.base.channel.eventbus.core.MassEvent;
import com.xa.mass.base.channel.eventbus.core.MassPlatformEventType;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class DeviceOnlineEvent extends MassEvent.BaseMassEvent {
    private final String deviceId;
    private final String reason;

    public DeviceOnlineEvent(String deviceId, String reason, String traceId) {
        super(
                "DEVICE_ONLINE",
                MassPlatformEventType.DEVICE_ONLINE_BATCH,
                String.format("Device %s is online: %s", deviceId, reason),
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
