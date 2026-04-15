package com.xa.mass.engine;

import com.xa.mass.base.channel.eventbus.event.device.DeviceOfflineEvent;
import com.xa.mass.base.channel.eventbus.event.device.DeviceOnlineEvent;
import com.xa.mass.base.enums.device.DeviceStatus;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.storage.InMemoryDeviceStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeviceManagerTest {

    private DeviceManager manager;

    @BeforeEach
    void setUp() {
        manager = new DeviceManager(new InMemoryDeviceStorage());
    }

    // ---- add / get ----

    @Test
    void addAndRetrieveDevice() {
        Device d = device("d1", "us");
        manager.addDevice(d);
        Device found = manager.getDevice("d1");
        assertNotNull(found);
        assertEquals("d1", found.getDeviceId());
    }

    @Test
    void getDeviceReturnsNullWhenNotFound() {
        assertNull(manager.getDevice("nonexistent"));
    }

    @Test
    void getAllDevicesReturnsAllAdded() {
        manager.addDevice(device("a", "us"));
        manager.addDevice(device("b", "gb"));
        manager.addDevice(device("c", "us"));
        assertEquals(3, manager.getAllDevices().size());
    }

    // ---- filter by group ----

    @Test
    void getDevicesByGroupIdFiltersCorrectly() {
        manager.addDevice(device("us1", "us"));
        manager.addDevice(device("us2", "us"));
        manager.addDevice(device("gb1", "gb"));

        List<Device> us = manager.getDevicesByGroupId("us");
        assertEquals(2, us.size());
        assertTrue(us.stream().allMatch(d -> "us".equals(d.getDeviceGroupId())));
    }

    // ---- update / delete ----

    @Test
    void updateDeviceReturnsTrue() {
        Device d = device("d2", "us");
        manager.addDevice(d);
        d.setStatus(DeviceStatus.OFFLINE);
        assertTrue(manager.updateDevice(d));
    }

    @Test
    void deleteDeviceRemovesIt() {
        manager.addDevice(device("d3", "us"));
        assertTrue(manager.deleteDevice("d3"));
        assertNull(manager.getDevice("d3"));
    }

    @Test
    void deleteNonexistentDeviceReturnsFalse() {
        assertFalse(manager.deleteDevice("ghost"));
    }

    // ---- token ----

    @Test
    void addAndRetrieveToken() {
        manager.addDevice(device("d4", "us"));
        Token token = new Token();
        token.setTokenId("tok-1");
        token.setDeviceId("d4");
        manager.addToken("d4", token);

        Token found = manager.getToken("d4");
        assertNotNull(found);
        assertEquals("tok-1", found.getTokenId());
    }

    @Test
    void deleteTokenRemovesIt() {
        manager.addDevice(device("d5", "us"));
        Token token = new Token();
        token.setTokenId("tok-2");
        manager.addToken("d5", token);
        assertTrue(manager.deleteToken("d5"));
        assertNull(manager.getToken("d5"));
    }

    // ---- lock ----

    @Test
    void lockAndUnlockDevice() {
        manager.addDevice(device("d6", "us"));
        assertTrue(manager.tryLockDevice("d6"));
        assertTrue(manager.isLocked("d6"));

        manager.unlockDevice("d6");
        assertFalse(manager.isLocked("d6"));
    }

    @Test
    void lockAlreadyLockedDeviceReturnsFalse() {
        manager.addDevice(device("d7", "us"));
        assertTrue(manager.tryLockDevice("d7"));
        assertFalse(manager.tryLockDevice("d7"));
    }

    // ---- online status ----

    @Test
    void onlineStatusTracking() {
        manager.addDevice(device("d8", "us"));
        manager.updateOnlineStatus("d8", false);
        assertFalse(manager.isDeviceOnline("d8"));
        assertEquals(DeviceStatus.OFFLINE, manager.getDevice("d8").getStatus());

        manager.updateOnlineStatus("d8", true);
        assertTrue(manager.isDeviceOnline("d8"));
        assertEquals(DeviceStatus.ONLINE, manager.getDevice("d8").getStatus());

        manager.updateOnlineStatus("d8", false);
        assertFalse(manager.isDeviceOnline("d8"));
        assertEquals(DeviceStatus.OFFLINE, manager.getDevice("d8").getStatus());
    }

    @Test
    void deviceStatusEventListenerKeepsModelStatusInSync() {
        DeviceManager.DeviceStatusEventListener listener = new DeviceManager.DeviceStatusEventListener(manager);
        manager.addDevice(device("d9", "us"));
        manager.updateOnlineStatus("d9", false);

        listener.onDeviceOnline(new DeviceOnlineEvent("d9", "connected", null));
        assertTrue(manager.isDeviceOnline("d9"));
        assertEquals(DeviceStatus.ONLINE, manager.getDevice("d9").getStatus());

        listener.onDeviceOffline(new DeviceOfflineEvent("d9", "disconnected", null));
        assertFalse(manager.isDeviceOnline("d9"));
        assertEquals(DeviceStatus.OFFLINE, manager.getDevice("d9").getStatus());
    }

    // ---- helpers ----

    private Device device(String id, String deviceGroupId) {
        Device d = new Device();
        d.setDeviceId(id);
        d.setDeviceGroupId(deviceGroupId);
        d.setStatus(DeviceStatus.ONLINE);
        return d;
    }
}
