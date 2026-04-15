package com.xa.mass.engine;

import com.xa.mass.base.channel.eventbus.event.device.DeviceOfflineEvent;
import com.xa.mass.base.channel.eventbus.event.device.DeviceOnlineEvent;
import com.xa.mass.base.enums.device.DeviceStatus;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.storage.DeviceStorage;
import com.xa.mass.engine.storage.TaskStorageFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Device and token access facade for the active engine runtime.
 *
 * <p>Online truth is owned by {@link Device#getStatus()}. This manager keeps the
 * convenience methods aligned with that single source of truth instead of
 * maintaining a second in-memory online registry.
 */
public class DeviceManager {

    private static final Logger log = LoggerFactory.getLogger(DeviceManager.class);

    private final DeviceStorage deviceStorage;

    public DeviceManager() {
        this(TaskStorageFactory.createDefaultDeviceStorage());
    }

    public DeviceManager(DeviceStorage deviceStorage) {
        this.deviceStorage = deviceStorage;
    }

    public void addDevice(Device device) {
        deviceStorage.addDevice(device);
    }

    public Device getDevice(String deviceId) {
        return deviceStorage.getDevice(deviceId).orElse(null);
    }

    public boolean updateDevice(Device device) {
        return deviceStorage.updateDevice(device);
    }

    public boolean deleteDevice(String deviceId) {
        return deviceStorage.deleteDevice(deviceId);
    }

    public List<Device> getDevicesByGroupId(String deviceGroupId) {
        return deviceStorage.getDevicesByGroupId(deviceGroupId);
    }

    public void addToken(String deviceId, Token token) {
        deviceStorage.addToken(deviceId, token);
    }

    public Token getToken(String deviceId) {
        return deviceStorage.getToken(deviceId).orElse(null);
    }

    public boolean updateToken(String deviceId, Token token) {
        return deviceStorage.updateToken(deviceId, token);
    }

    public boolean deleteToken(String deviceId) {
        return deviceStorage.deleteToken(deviceId);
    }

    public boolean tryLockDevice(String deviceId) {
        return deviceStorage.tryLockDevice(deviceId);
    }

    public void unlockDevice(String deviceId) {
        deviceStorage.unlockDevice(deviceId);
    }

    public boolean isLocked(String deviceId) {
        return deviceStorage.isLocked(deviceId);
    }

    public List<Device> getAllDevices() {
        return deviceStorage.getAllDevices();
    }

    public List<Token> getAllTokens() {
        return deviceStorage.getAllTokens();
    }

    public List<String> getLockedDevices() {
        return deviceStorage.getLockedDevices();
    }

    /**
     * Updates the device model status so online checks and matching rules read a
     * single truth source.
     */
    public void updateOnlineStatus(String deviceId, boolean online) {
        Device device = getDevice(deviceId);
        if (device == null) {
            if (!online) {
                return;
            }
            device = new Device();
            device.setDeviceId(deviceId);
            addDevice(device);
        }

        device.transitionTo(online ? DeviceStatus.ONLINE : DeviceStatus.OFFLINE);
        updateDevice(device);
    }

    public boolean isDeviceOnline(String deviceId) {
        Device device = getDevice(deviceId);
        return device != null && device.getStatus() == DeviceStatus.ONLINE;
    }

    /**
     * Event listener that keeps device model state synchronized with gateway
     * connect/disconnect events.
     */
    public static class DeviceStatusEventListener {
        private final DeviceManager deviceManager;

        public DeviceStatusEventListener(DeviceManager deviceManager) {
            this.deviceManager = deviceManager;
        }

        @com.google.common.eventbus.Subscribe
        public void onDeviceOnline(DeviceOnlineEvent event) {
            log.info("Device online: {}", event.getDeviceId());
            String deviceId = event.getDeviceId();
            Device device = deviceManager.getDevice(deviceId);
            if (device == null) {
                device = new Device();
                device.setDeviceId(deviceId);
                deviceManager.addDevice(device);
            }
            device.updateHeartbeat();
            deviceManager.updateDevice(device);
        }

        @com.google.common.eventbus.Subscribe
        public void onDeviceOffline(DeviceOfflineEvent event) {
            deviceManager.updateOnlineStatus(event.getDeviceId(), false);
        }
    }
}
