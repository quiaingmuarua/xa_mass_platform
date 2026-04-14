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
 * 鐠佹儳顦粻锛勬倞閸?
 * 鐠愮喕鐭楃拋鎯ь槵閻ㄥ嚋RUD閹垮秳缍旈崪瀛磑ken缁狅紕鎮?
 */
public class DeviceManager {

    private static final Logger log = LoggerFactory.getLogger(DeviceManager.class);
    private final DeviceStorage deviceStorage;

    // 閸︺劎鍤庨悩鑸碘偓浣侯吀閻?
    private final Set<String> onlineDevices = new ConcurrentSkipListSet<>();

    public DeviceManager() {
        this(TaskStorageFactory.createDefaultDeviceStorage());
    }

    public DeviceManager(DeviceStorage deviceStorage) {
        this.deviceStorage = deviceStorage;
    }

    /**
     * 濞ｈ濮炵拋鎯ь槵
     */
    public void addDevice(Device device) {
        deviceStorage.addDevice(device);
    }

    /**
     * 閺嶈宓佺拋鎯ь槵ID閼惧嘲褰囩拋鎯ь槵
     */
    public Device getDevice(String deviceId) {
        return deviceStorage.getDevice(deviceId).orElse(null);
    }

    /**
     * 閺囧瓨鏌婄拋鎯ь槵
     */
    public boolean updateDevice(Device device) {
        return deviceStorage.updateDevice(device);
    }

    /**
     * 閸掔娀娅庣拋鎯ь槵
     */
    public boolean deleteDevice(String deviceId) {
        return deviceStorage.deleteDevice(deviceId);
    }

    /**
     * 閺嶈宓侀崶钘夘啀閼惧嘲褰囩拋鎯ь槵閸掓銆?
     */
    public List<Device> getDevicesByGroupId(String deviceGroupId) {
        return deviceStorage.getDevicesByGroupId(deviceGroupId);
    }

    /**
     * 濞ｈ濮濼oken
     */
    public void addToken(String deviceId, Token token) {
        deviceStorage.addToken(deviceId, token);
    }

    /**
     * 閺嶈宓佺拋鎯ь槵ID閼惧嘲褰嘥oken
     */
    public Token getToken(String deviceId) {
        return deviceStorage.getToken(deviceId).orElse(null);
    }

    /**
     * 閺囧瓨鏌奣oken
     */
    public boolean updateToken(String deviceId, Token token) {
        return deviceStorage.updateToken(deviceId, token);
    }

    /**
     * 閸掔娀娅嶵oken
     */
    public boolean deleteToken(String deviceId) {
        return deviceStorage.deleteToken(deviceId);
    }

    /**
     * 鐏忔繆鐦柨浣哥暰鐠佹儳顦?
     */
    public boolean tryLockDevice(String deviceId) {
        return deviceStorage.tryLockDevice(deviceId);
    }

    /**
     * 鐟欙綁鏀ｇ拋鎯ь槵
     */
    public void unlockDevice(String deviceId) {
        deviceStorage.unlockDevice(deviceId);
    }

    /**
     * 濡偓閺屻儴顔曟径鍥ㄦЦ閸氾箒顫﹂柨浣哥暰
     */
    public boolean isLocked(String deviceId) {
        return deviceStorage.isLocked(deviceId);
    }

    /**
     * 閼惧嘲褰囬幍鈧張澶庮啎婢?
     */
    public List<Device> getAllDevices() {
        return deviceStorage.getAllDevices();
    }

    /**
     * 閼惧嘲褰囬幍鈧張濉梠ken
     */
    public List<Token> getAllTokens() {
        return deviceStorage.getAllTokens();
    }

    /**
     * 閼惧嘲褰囬幍鈧張澶愭敚鐎规氨娈戠拋鎯ь槵ID
     */
    public List<String> getLockedDevices() {
        return deviceStorage.getLockedDevices();
    }

    // 閸︺劎鍤庨悩鑸碘偓浣侯吀閻?
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


    // 娴滃娆㈤惄鎴濇儔閸?
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
