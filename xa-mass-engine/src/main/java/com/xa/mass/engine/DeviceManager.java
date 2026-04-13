package com.xa.mass.engine;

import com.xa.mass.base.channel.eventbus.event.device.DeviceOfflineEvent;
import com.xa.mass.base.channel.eventbus.event.device.DeviceOnlineEvent;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.storage.DeviceStorage;
import com.xa.mass.engine.storage.TaskStorageFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * 璁惧绠＄悊鍣?
 * 璐熻矗璁惧鐨凜RUD鎿嶄綔鍜孴oken绠＄悊
 */
public class DeviceManager {

    private static final Logger log = LoggerFactory.getLogger(DeviceManager.class);
    private final DeviceStorage deviceStorage;

    // 鍦ㄧ嚎鐘舵€佺鐞?
    private final Set<String> onlineDevices = new ConcurrentSkipListSet<>();

    public DeviceManager() {
        this(TaskStorageFactory.createDefaultDeviceStorage());
    }

    public DeviceManager(DeviceStorage deviceStorage) {
        this.deviceStorage = deviceStorage;
    }

    /**
     * 娣诲姞璁惧
     */
    public void addDevice(Device device) {
        deviceStorage.addDevice(device);
    }

    /**
     * 鏍规嵁璁惧ID鑾峰彇璁惧
     */
    public Device getDevice(String deviceId) {
        return deviceStorage.getDevice(deviceId).orElse(null);
    }

    /**
     * 鏇存柊璁惧
     */
    public boolean updateDevice(Device device) {
        return deviceStorage.updateDevice(device);
    }

    /**
     * 鍒犻櫎璁惧
     */
    public boolean deleteDevice(String deviceId) {
        return deviceStorage.deleteDevice(deviceId);
    }

    /**
     * 鏍规嵁鍥藉鑾峰彇璁惧鍒楄〃
     */
    public List<Device> getDevicesByCountry(String country) {
        return deviceStorage.getDevicesByCountry(country);
    }

    /**
     * 娣诲姞Token
     */
    public void addToken(String deviceId, Token token) {
        deviceStorage.addToken(deviceId, token);
    }

    /**
     * 鏍规嵁璁惧ID鑾峰彇Token
     */
    public Token getToken(String deviceId) {
        return deviceStorage.getToken(deviceId).orElse(null);
    }

    /**
     * 鏇存柊Token
     */
    public boolean updateToken(String deviceId, Token token) {
        return deviceStorage.updateToken(deviceId, token);
    }

    /**
     * 鍒犻櫎Token
     */
    public boolean deleteToken(String deviceId) {
        return deviceStorage.deleteToken(deviceId);
    }

    /**
     * 灏濊瘯閿佸畾璁惧
     */
    public boolean tryLockDevice(String deviceId) {
        return deviceStorage.tryLockDevice(deviceId);
    }

    /**
     * 瑙ｉ攣璁惧
     */
    public void unlockDevice(String deviceId) {
        deviceStorage.unlockDevice(deviceId);
    }

    /**
     * 妫€鏌ヨ澶囨槸鍚﹁閿佸畾
     */
    public boolean isLocked(String deviceId) {
        return deviceStorage.isLocked(deviceId);
    }

    /**
     * 鑾峰彇鎵€鏈夎澶?
     */
    public List<Device> getAllDevices() {
        return deviceStorage.getAllDevices();
    }

    /**
     * 鑾峰彇鎵€鏈塗oken
     */
    public List<Token> getAllTokens() {
        return deviceStorage.getAllTokens();
    }

    /**
     * 鑾峰彇鎵€鏈夐攣瀹氱殑璁惧ID
     */
    public List<String> getLockedDevices() {
        return deviceStorage.getLockedDevices();
    }

    // 鍦ㄧ嚎鐘舵€佺鐞?
    public void updateOnlineStatus(String deviceId, boolean online) {
        if (online) {
            onlineDevices.add(deviceId);
        } else {
            onlineDevices.remove(deviceId);
        }
    }

    public boolean isDeviceOnline(String deviceId) {
        return onlineDevices.contains(deviceId);
    }


    // 浜嬩欢鐩戝惉鍣?
    public static class DeviceStatusEventListener {
        private final DeviceManager deviceManager;

        public DeviceStatusEventListener(DeviceManager deviceManager) {
            this.deviceManager = deviceManager;
        }

        @com.google.common.eventbus.Subscribe
        public void onDeviceOnline(DeviceOnlineEvent event) {
            log.info("Device_online: {}", event.getDeviceId());
            String deviceId = event.getDeviceId();
            com.xa.mass.base.model.Device device = deviceManager.getDevice(deviceId);
            if (device == null) {
                device = new com.xa.mass.base.model.Device();
                device.setDeviceId(deviceId);
                device.setStatus(com.xa.mass.base.enums.device.DeviceStatus.ONLINE);
                deviceManager.addDevice(device);
            }
            device.updateHeartbeat();
            deviceManager.updateOnlineStatus(deviceId, true);
        }

        @com.google.common.eventbus.Subscribe
        public void onDeviceOffline(DeviceOfflineEvent event) {
            String deviceId = event.getDeviceId();
            deviceManager.updateOnlineStatus(deviceId, false);
        }
    }
} 
