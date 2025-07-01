package com.xa.mass.engine;

import com.xa.mass.engine.storage.DeviceStorage;
import com.xa.mass.engine.storage.TaskStorageFactory;
import com.xa.mass.base.enums.device.DeviceStatus;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Token;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * 设备管理器
 * 负责设备的CRUD操作和Token管理
 */
public class DeviceManager {
    
    private final DeviceStorage deviceStorage;
    
    // 在线状态管理
    private final Set<String> onlineDevices = new ConcurrentSkipListSet<>();
    
    public DeviceManager() {
        this(TaskStorageFactory.createDefaultDeviceStorage());
        com.xa.mass.base.event.EventBusManager.register(new DeviceStatusEventListener(this));
    }
    
    public DeviceManager(DeviceStorage deviceStorage) {
        this.deviceStorage = deviceStorage;
        com.xa.mass.base.event.EventBusManager.register(new DeviceStatusEventListener(this));
    }

    /**
     * 添加设备
     */
    public void addDevice(Device device) {
        deviceStorage.addDevice(device);
    }
    
    /**
     * 根据设备ID获取设备
     */
    public Device getDevice(String deviceId) {
        return deviceStorage.getDevice(deviceId).orElse(null);
    }
    
    /**
     * 更新设备
     */
    public boolean updateDevice(Device device) {
        return deviceStorage.updateDevice(device);
    }
    
    /**
     * 删除设备
     */
    public boolean deleteDevice(String deviceId) {
        return deviceStorage.deleteDevice(deviceId);
    }

    /**
     * 根据国家获取设备列表
     */
    public List<Device> getDevicesByCountry(String country) {
        return deviceStorage.getDevicesByCountry(country);
    }

    /**
     * 添加Token
     */
    public void addToken(String deviceId, Token token) {
        deviceStorage.addToken(deviceId, token);
    }

    /**
     * 根据设备ID获取Token
     */
    public Token getToken(String deviceId) {
        return deviceStorage.getToken(deviceId).orElse(null);
    }
    
    /**
     * 更新Token
     */
    public boolean updateToken(String deviceId, Token token) {
        return deviceStorage.updateToken(deviceId, token);
    }
    
    /**
     * 删除Token
     */
    public boolean deleteToken(String deviceId) {
        return deviceStorage.deleteToken(deviceId);
    }

    /**
     * 尝试锁定设备
     */
    public boolean tryLockDevice(String deviceId) {
        return deviceStorage.tryLockDevice(deviceId);
    }

    /**
     * 解锁设备
     */
    public void unlockDevice(String deviceId) {
        deviceStorage.unlockDevice(deviceId);
    }

    /**
     * 检查设备是否被锁定
     */
    public boolean isLocked(String deviceId) {
        return deviceStorage.isLocked(deviceId);
    }

    /**
     * 获取所有设备
     */
    public List<Device> getAllDevices() {
        return deviceStorage.getAllDevices();
    }

    /**
     * 获取所有Token
     */
    public List<Token> getAllTokens() {
        return deviceStorage.getAllTokens();
    }
    
    /**
     * 获取所有锁定的设备ID
     */
    public List<String> getLockedDevices() {
        return deviceStorage.getLockedDevices();
    }

    // 在线状态管理
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



    // 事件监听器
    public static class DeviceStatusEventListener {
        private final DeviceManager deviceManager;
        public DeviceStatusEventListener(DeviceManager deviceManager) {
            this.deviceManager = deviceManager;
        }
        @com.google.common.eventbus.Subscribe
        public void onDeviceOnline(com.xa.mass.base.event.DeviceEvent.DeviceOnlineBatchEvent event) {
            if (event.getDevices() != null && !event.getDevices().isEmpty()) {
                for (com.xa.mass.base.model.Device device : event.getDevices()) {
                    deviceManager.addDevice(device);
                    deviceManager.updateOnlineStatus(device.getDeviceId(), true);
                }
            } else {
                for (String deviceId : event.getDeviceIds()) {
                    if (deviceManager.getDevice(deviceId) == null) {
                        com.xa.mass.base.model.Device device = new com.xa.mass.base.model.Device();
                        device.setDeviceId(deviceId);
                        device.setStatus(DeviceStatus.ONLINE);
                        deviceManager.addDevice(device);
                    }
                    deviceManager.updateOnlineStatus(deviceId, true);
                }
            }
        }
        @com.google.common.eventbus.Subscribe
        public void onDeviceOffline(com.xa.mass.base.event.DeviceEvent.DeviceOfflineSingleEvent event) {
            deviceManager.updateOnlineStatus(event.getDeviceId(), false);
        }
    }
} 