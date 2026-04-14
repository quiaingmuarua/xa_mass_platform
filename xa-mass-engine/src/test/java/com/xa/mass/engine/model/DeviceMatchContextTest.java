package com.xa.mass.engine.model;

import com.xa.mass.base.enums.Project;
import com.xa.mass.base.enums.device.DeviceStatus;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TokenStatus;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.storage.InMemoryDeviceStorage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DeviceMatchContextTest {

    @Test
    void contextIncludesNestedReadOnlyAttributesMaps() {
        Device device = new Device();
        device.setDeviceId("device-1");
        device.setStatus(DeviceStatus.ONLINE);
        device.setDeviceGroupId("us");
        device.setSupportedProjects(List.of(Project.DEMO_APP));
        device.setAttributes(Map.of("pool", "warmup"));

        Token token = new Token();
        token.setTokenId("token-1");
        token.setDeviceId("device-1");
        token.setStatus(TokenStatus.LOGIN_READY);
        token.setChannel("us");
        token.setAttributes(Map.of("country", "us"));

        Task task = new Task();
        task.setTid("task-1");
        task.setProject(Project.DEMO_APP);
        task.setTaskRoutingCountryCode("us");
        task.setStatus(TaskStatus.READY);

        DeviceManager deviceManager = new DeviceManager(new InMemoryDeviceStorage());

        DeviceMatchContext context = new DeviceMatchContext(device, token, task, deviceManager);

        assertEquals(Map.of("pool", "warmup"), context.getContext().get("deviceAttributes"));
        assertEquals(Map.of("country", "us"), context.getContext().get("tokenAttributes"));
        assertEquals(0, context.getContext().get("taskTargetNumber"));
        assertEquals("us", context.getContext().get("taskRoutingCountryCode"));
        assertEquals(true, context.getContext().get("deviceGroupIdEqualsRoutingCountry"));
        assertEquals(true, context.getContext().get("tokenChannelMatchesRoutingCountry"));
        assertEquals(true, context.getContext().get("tokenAttributeCountryMatchesRoutingCountry"));
    }

    @Test
    void contextUsesEmptyTokenAttributesWhenTokenMissing() {
        Device device = new Device();
        device.setDeviceId("device-2");
        device.setStatus(DeviceStatus.ONLINE);
        device.setDeviceGroupId("us");
        device.setSupportedProjects(List.of(Project.DEMO_APP));

        Task task = new Task();
        task.setTid("task-2");
        task.setProject(Project.DEMO_APP);
        task.setTaskRoutingCountryCode("us");
        task.setStatus(TaskStatus.READY);

        DeviceManager deviceManager = new DeviceManager(new InMemoryDeviceStorage());

        DeviceMatchContext context = new DeviceMatchContext(device, null, task, deviceManager);

        assertEquals(Map.of(), context.getContext().get("tokenAttributes"));
        assertFalse((Boolean) context.getContext().get("isTokenAllocatable"));
    }
}
