package com.xa.mass.engine.storage;

import com.xa.mass.eventbus.model.Device;
import com.xa.mass.eventbus.model.Token;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存设备存储实现
 * 使用ConcurrentHashMap和Collections.synchronizedSet保证线程安全
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
            // 同时删除相关的Token和锁定状态
            deviceToken.remove(deviceId);
            lockedDevices.remove(deviceId);
        }
        return removed != null;
    }
    
    @Override
    public List<Device> getDevicesByCountry(String country) {
        return devices.values().stream()
                .filter(d -> d.getGroupId().equals(country))
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