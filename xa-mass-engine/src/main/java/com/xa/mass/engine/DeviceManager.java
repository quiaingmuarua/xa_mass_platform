package com.xa.mass.engine;

import com.xa.mass.eventbus.model.Device;
import com.xa.mass.eventbus.model.Token;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class DeviceManager {
    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private final Map<String, Token> deviceToken = new ConcurrentHashMap<>();
    private final Set<String> lockedDevices = Collections.synchronizedSet(new HashSet<>());

    public void addDevice(Device device) {
        devices.put(device.getDeviceId(), device);
    }

    public void addToken(String deviceId, Token token) {
        deviceToken.put(deviceId, token);
    }

    public List<Device> getDevicesByCountry(String country) {
        return devices.values().stream()
                .filter(d -> d.getGroupId().equals(country))
                .collect(Collectors.toList());
    }

    public Token getToken(String deviceId) {
        return deviceToken.get(deviceId);
    }

    public boolean tryLockDevice(String deviceId) {
        return lockedDevices.add(deviceId);
    }

    public void unlockDevice(String deviceId) {
        lockedDevices.remove(deviceId);
    }

    public boolean isLocked(String deviceId) {
        return lockedDevices.contains(deviceId);
    }
} 