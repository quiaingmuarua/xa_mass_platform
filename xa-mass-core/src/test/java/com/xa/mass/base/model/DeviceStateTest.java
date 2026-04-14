package com.xa.mass.base.model;

import com.xa.mass.base.enums.Project;
import com.xa.mass.base.enums.device.DeviceStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceStateTest {

    @Test
    void constructorInitializesStableDefaults() {
        Device device = new Device();

        assertEquals(DeviceStatus.OFFLINE, device.getStatus());
        assertTrue(device.getSupportedProjects().isEmpty());
        assertNotNull(device.getCreateTime());
        assertNotNull(device.getUpdateTime());
    }

    @Test
    void supportedProjectsAreCopiedAndReadOnly() {
        Device device = new Device();
        List<Project> input = new ArrayList<>();
        input.add(Project.DEMO_APP);

        device.setSupportedProjects(input);
        input.clear();

        assertEquals(List.of(Project.DEMO_APP), device.getSupportedProjects());
        assertThrows(UnsupportedOperationException.class,
                () -> device.getSupportedProjects().add(Project.TEST_APP));
    }

    @Test
    void deviceStateTransitionsFollowExplicitRules() {
        Device device = new Device();

        assertTrue(device.transitionTo(DeviceStatus.ONLINE));
        assertEquals(DeviceStatus.ONLINE, device.getStatus());

        assertTrue(device.transitionTo(DeviceStatus.EXPIRED));
        assertEquals(DeviceStatus.EXPIRED, device.getStatus());

        assertTrue(device.transitionTo(DeviceStatus.OFFLINE));
        assertEquals(DeviceStatus.OFFLINE, device.getStatus());

        assertFalse(device.transitionTo(DeviceStatus.OFFLINE));
        assertFalse(device.transitionTo(null));
    }

    @Test
    void heartbeatRevivesExpiredDeviceToOnline() {
        Device device = new Device();
        device.transitionTo(DeviceStatus.ONLINE);
        device.transitionTo(DeviceStatus.EXPIRED);

        device.updateHeartbeat();

        assertEquals(DeviceStatus.ONLINE, device.getStatus());
        assertNotNull(device.getLastHeartbeat());
    }

    @Test
    void setStatusRejectsNull() {
        Device device = new Device();

        assertThrows(NullPointerException.class, () -> device.setStatus(null));
    }

    @Test
    void heartbeatExpiryDependsOnLastHeartbeatTimestamp() {
        Device device = new Device();
        device.setLastHeartbeat(LocalDateTime.now().minusSeconds(60));

        assertTrue(device.isHeartbeatExpired(30));
        assertFalse(device.isHeartbeatExpired(120));
    }
}
