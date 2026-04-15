package com.xa.mass.engine.storage;

import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Token;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 鍐呭瓨璁惧瀛樺偍瀹炵幇
 * 浣跨敤ConcurrentHashMap鍜孋ollections.synchronizedSet淇濊瘉绾跨▼瀹夊叏
 */
public class InMemoryDeviceStorage implements DeviceStorage {

    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private final Map<String, Token> deviceToken = new ConcurrentHashMap<>();
    private final Set<String> lockedDevices = Collections.synchronizedSet(new HashSet<>());

    @Override
    public void addDevice(Device device) {
        devices.put(device.getDeviceId(), device);
    }

    @Override
    public Optional<Device> getDevice(String deviceId) {
        return Optional.ofNullable(devices.get(deviceId));
    }

    @Override
    public boolean updateDevice(Device device) {
        if (device.getDeviceId() == null || !devices.containsKey(device.getDeviceId())) {
            return false;
        }
        devices.put(device.getDeviceId(), device);
        return true;
    }

    @Override
    public boolean deleteDevice(String deviceId) {
        Device removed = devices.remove(deviceId);
        if (removed != null) {
            // 鍚屾椂鍒犻櫎鐩稿叧鐨凾oken鍜岄攣瀹氱姸鎬?
            deviceToken.remove(deviceId);
            lockedDevices.remove(deviceId);
        }
        return removed != null;
    }

    @Override
    public List<Device> getDevicesByGroupId(String deviceGroupId) {
        return devices.values().stream()
                .filter(d -> deviceGroupId != null && deviceGroupId.equals(d.getDeviceGroupId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<Device> getAllDevices() {
        return new ArrayList<>(devices.values());
    }

    @Override
    public void addToken(String deviceId, Token token) {
        deviceToken.put(deviceId, token);
    }

    @Override
    public Optional<Token> getToken(String deviceId) {
        return Optional.ofNullable(deviceToken.get(deviceId));
    }

    @Override
    public boolean updateToken(String deviceId, Token token) {
        if (!deviceToken.containsKey(deviceId)) {
            return false;
        }
        deviceToken.put(deviceId, token);
        return true;
    }

    @Override
    public boolean deleteToken(String deviceId) {
        Token removed = deviceToken.remove(deviceId);
        return removed != null;
    }

    @Override
    public List<Token> getAllTokens() {
        return new ArrayList<>(deviceToken.values());
    }

    @Override
    public boolean tryLockDevice(String deviceId) {
        return lockedDevices.add(deviceId);
    }

    @Override
    public void unlockDevice(String deviceId) {
        lockedDevices.remove(deviceId);
    }

    @Override
    public boolean isLocked(String deviceId) {
        return lockedDevices.contains(deviceId);
    }

    @Override
    public List<String> getLockedDevices() {
        return new ArrayList<>(lockedDevices);
    }
} 
