package com.xa.mass.base.eventbus.device;

import com.xa.mass.base.eventbus.core.MassEvent;
import com.xa.mass.base.eventbus.core.MassPlatformEventType;
import com.xa.mass.base.model.Device;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备批量上线事件
 */
public class DeviceOnlineBatchEvent extends MassEvent.BaseMassEvent {
    private final List<String> deviceIds;
    private final String reason;
    private final List<Device> devices;

    public DeviceOnlineBatchEvent(List<String> deviceIds, List<Device> devices, String reason, String traceId) {
        super(
                "DEVICE_ONLINE_BATCH",
                MassPlatformEventType.DEVICE_ONLINE_BATCH,
                String.format("设备批量上线，设备数: %d，原因: %s", deviceIds.size(), reason),
                createMetadata(deviceIds, reason),
                traceId,
                null
        );
        this.deviceIds = deviceIds;
        this.devices = devices;
        this.reason = reason;
    }

    private static Map<String, Object> createMetadata(List<String> deviceIds, String reason) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("deviceIds", deviceIds);
        metadata.put("reason", reason);
        metadata.put("deviceCount", deviceIds.size());
        return Collections.unmodifiableMap(metadata);
    }

    public List<String> getDeviceIds() { return deviceIds; }
    public String getReason() { return reason; }
    public List<Device> getDevices() { return devices; }
}
