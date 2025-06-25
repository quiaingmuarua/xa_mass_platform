package com.xa.mass.eventbus.event;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备状态相关事件
 */
public class DeviceEvent {
    
    /**
     * 设备批量下线事件
     */
    public static class DeviceOfflineBatchEvent extends ChaosEvent.BaseChaosEvent {
        private final List<String> deviceIds;
        private final String reason;
        private final long offlineDurationMs;
        
        public DeviceOfflineBatchEvent(List<String> deviceIds, String reason, long offlineDurationMs) {
            super(ChaosEventType.DEVICE_OFFLINE_BATCH,
                  deviceIds.size() > 10 ? ChaosEventSeverity.HIGH : ChaosEventSeverity.MEDIUM,
                  createMetadata(deviceIds, reason, offlineDurationMs),
                  String.format("设备批量下线，设备数: %d，原因: %s，下线时长: %dms", 
                              deviceIds.size(), reason, offlineDurationMs));
            this.deviceIds = deviceIds;
            this.reason = reason;
            this.offlineDurationMs = offlineDurationMs;
        }
        
        private static Map<String, Object> createMetadata(List<String> deviceIds, String reason, long offlineDurationMs) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("deviceIds", deviceIds);
            metadata.put("reason", reason);
            metadata.put("offlineDurationMs", offlineDurationMs);
            metadata.put("deviceCount", deviceIds.size());
            return metadata;
        }
        
        public List<String> getDeviceIds() {
            return deviceIds;
        }
        
        public String getReason() {
            return reason;
        }
        
        public long getOfflineDurationMs() {
            return offlineDurationMs;
        }
    }
    
    /**
     * 设备单个下线事件
     */
    public static class DeviceOfflineSingleEvent extends ChaosEvent.BaseChaosEvent {
        private final String deviceId;
        private final String reason;
        private final long offlineDurationMs;
        
        public DeviceOfflineSingleEvent(String deviceId, String reason, long offlineDurationMs) {
            super(ChaosEventType.DEVICE_OFFLINE_SINGLE,
                  offlineDurationMs > 300000 ? ChaosEventSeverity.HIGH : ChaosEventSeverity.LOW,
                  createMetadata(deviceId, reason, offlineDurationMs),
                  String.format("设备 %s 下线，原因: %s，下线时长: %dms", deviceId, reason, offlineDurationMs));
            this.deviceId = deviceId;
            this.reason = reason;
            this.offlineDurationMs = offlineDurationMs;
        }
        
        private static Map<String, Object> createMetadata(String deviceId, String reason, long offlineDurationMs) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("deviceId", deviceId);
            metadata.put("reason", reason);
            metadata.put("offlineDurationMs", offlineDurationMs);
            return metadata;
        }
        
        public String getDeviceId() {
            return deviceId;
        }
        
        public String getReason() {
            return reason;
        }
        
        public long getOfflineDurationMs() {
            return offlineDurationMs;
        }
    }
    
    /**
     * 设备闪断事件
     */
    public static class DeviceFlashDisconnectEvent extends ChaosEvent.BaseChaosEvent {
        private final String deviceId;
        private final int disconnectCount;
        private final long disconnectIntervalMs;
        
        public DeviceFlashDisconnectEvent(String deviceId, int disconnectCount, long disconnectIntervalMs) {
            super(ChaosEventType.DEVICE_FLASH_DISCONNECT,
                  disconnectCount > 5 ? ChaosEventSeverity.HIGH : ChaosEventSeverity.MEDIUM,
                  createMetadata(deviceId, disconnectCount, disconnectIntervalMs),
                  String.format("设备 %s 闪断，断开次数: %d，间隔: %dms", 
                              deviceId, disconnectCount, disconnectIntervalMs));
            this.deviceId = deviceId;
            this.disconnectCount = disconnectCount;
            this.disconnectIntervalMs = disconnectIntervalMs;
        }
        
        private static Map<String, Object> createMetadata(String deviceId, int disconnectCount, long disconnectIntervalMs) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("deviceId", deviceId);
            metadata.put("disconnectCount", disconnectCount);
            metadata.put("disconnectIntervalMs", disconnectIntervalMs);
            return metadata;
        }
        
        public String getDeviceId() {
            return deviceId;
        }
        
        public int getDisconnectCount() {
            return disconnectCount;
        }
        
        public long getDisconnectIntervalMs() {
            return disconnectIntervalMs;
        }
    }
    
    /**
     * 设备长时间不归队事件
     */
    public static class DeviceLongAbsenceEvent extends ChaosEvent.BaseChaosEvent {
        private final String deviceId;
        private final long absenceDurationMs;
        private final String lastSeenTime;
        
        public DeviceLongAbsenceEvent(String deviceId, long absenceDurationMs, String lastSeenTime) {
            super(ChaosEventType.DEVICE_LONG_ABSENCE,
                  absenceDurationMs > 3600000 ? ChaosEventSeverity.HIGH : ChaosEventSeverity.MEDIUM,
                  createMetadata(deviceId, absenceDurationMs, lastSeenTime),
                  String.format("设备 %s 长时间不归队，缺席时长: %dms，最后在线: %s", 
                              deviceId, absenceDurationMs, lastSeenTime));
            this.deviceId = deviceId;
            this.absenceDurationMs = absenceDurationMs;
            this.lastSeenTime = lastSeenTime;
        }
        
        private static Map<String, Object> createMetadata(String deviceId, long absenceDurationMs, String lastSeenTime) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("deviceId", deviceId);
            metadata.put("absenceDurationMs", absenceDurationMs);
            metadata.put("lastSeenTime", lastSeenTime);
            return metadata;
        }
        
        public String getDeviceId() {
            return deviceId;
        }
        
        public long getAbsenceDurationMs() {
            return absenceDurationMs;
        }
        
        public String getLastSeenTime() {
            return lastSeenTime;
        }
    }
    
    /**
     * 设备批量上线事件
     */
    public static class DeviceOnlineBatchEvent extends ChaosEvent.BaseChaosEvent {
        private final List<String> deviceIds;
        private final String reason;
        
        public DeviceOnlineBatchEvent(List<String> deviceIds, String reason) {
            super(ChaosEventType.DEVICE_ONLINE_BATCH,
                  ChaosEventSeverity.LOW,
                  createMetadata(deviceIds, reason),
                  String.format("设备批量上线，设备数: %d，原因: %s", deviceIds.size(), reason));
            this.deviceIds = deviceIds;
            this.reason = reason;
        }
        
        private static Map<String, Object> createMetadata(List<String> deviceIds, String reason) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("deviceIds", deviceIds);
            metadata.put("reason", reason);
            metadata.put("deviceCount", deviceIds.size());
            return metadata;
        }
        
        public List<String> getDeviceIds() {
            return deviceIds;
        }
        
        public String getReason() {
            return reason;
        }
    }
} 