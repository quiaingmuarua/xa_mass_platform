package com.xa.mass.base.eventbus.device;

import com.xa.mass.base.eventbus.core.MassEvent;
import com.xa.mass.base.model.Device;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 设备单个上线事件
 */
public class DeviceOnlineSingleEvent extends MassEvent.BaseMassEvent {
    private final String deviceId;
    private final String reason;
    private final Device device;

    public DeviceOnlineSingleEvent(String deviceId, Device device, String reason, String traceId) {
        super(
                "DEVICE_ONLINE_SINGLE",
                null,
                String.format("设备 %s 上线，原因: %s", deviceId, reason),
                createMetadata(deviceId, reason),
                traceId,
                null
        );
        this.deviceId = deviceId;
        this.device = device;
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

    public Device getDevice() {
        return device;
    }
} 