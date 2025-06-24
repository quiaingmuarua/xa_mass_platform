package com.xa.mass.engine;

import com.xa.mass.engine.model.Device;
import com.xa.mass.engine.model.Token;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class DeviceManager {
    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private final Map<String, List<Token>> deviceTokens = new ConcurrentHashMap<>();
    private final Set<String> lockedDevices = Collections.synchronizedSet(new HashSet<>());

    public void addDevice(Device device) {
        devices.put(device.getDeviceId(), device);
    }

    public void addToken(String deviceId, Token token) {
        deviceTokens.computeIfAbsent(deviceId, k -> new ArrayList<>()).add(token);
    }

    public List<Device> getDevicesByCountry(String country) {
        return devices.values().stream()
                .filter(d -> d.getGroupId().equals(country))
                .collect(Collectors.toList());
    }

    public List<Token> getTokens(String deviceId) {
        return deviceTokens.getOrDefault(deviceId, Collections.emptyList());
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