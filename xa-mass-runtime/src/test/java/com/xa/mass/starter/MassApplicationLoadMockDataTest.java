package com.xa.mass.starter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xa.mass.base.enums.Project;
import com.xa.mass.base.enums.task.TokenStatus;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.storage.InMemoryDeviceStorage;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.GatewayConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MassApplicationLoadMockDataTest {

    @Test
    void loadMockDataSeedsSupportedProjectsAndTokensForMockDevices() {
        DeviceManager deviceManager = new DeviceManager(new InMemoryDeviceStorage());
        MassEngine engine = mock(MassEngine.class);
        AtomicInteger createdTasks = new AtomicInteger();

        when(engine.getDeviceManager()).thenReturn(deviceManager);
        doAnswer(invocation -> {
            Device device = invocation.getArgument(0);
            deviceManager.addDevice(device);
            return null;
        }).when(engine).addDevice(any(Device.class));
        doAnswer(invocation -> {
            Token token = invocation.getArgument(0);
            deviceManager.addToken(token.getDeviceId(), token);
            return null;
        }).when(engine).addToken(any(Token.class));
        doAnswer(invocation -> {
            createdTasks.incrementAndGet();
            return new Task();
        }).when(engine).createTask(any(TaskCreateRequestDto.class));

        EngineConfig engineConfig = new EngineConfig();
        engineConfig.setMockConfigRoot(buildMockConfigRoot());
        MassApplication application = new MassApplication(engine, 8088, "/ws", new GatewayConfig(), engineConfig);

        application.loadMockData(engine, engineConfig);

        List<Device> devices = deviceManager.getAllDevices();
        List<Token> tokens = deviceManager.getAllTokens();

        assertEquals(2, devices.size());
        assertEquals(devices.size(), tokens.size());
        assertEquals(1, createdTasks.get());
        assertFalse(devices.isEmpty());

        Device sampleDevice = devices.get(0);
        assertNotNull(sampleDevice.getSupportedProjects());
        assertFalse(sampleDevice.getSupportedProjects().isEmpty());
        Project firstProject = sampleDevice.getSupportedProjects().get(0);
        assertNotNull(firstProject);

        assertTrue(devices.stream().allMatch(device ->
                device.getSupportedProjects() != null && !device.getSupportedProjects().isEmpty()));
        assertTrue(devices.stream().allMatch(device -> device.supportsProject("demoApp")));
        assertTrue(devices.stream().allMatch(device -> device.supportsProject("testApp")));
        assertTrue(tokens.stream().allMatch(token -> token.getStatus() == TokenStatus.LOGIN_READY));
        assertTrue(tokens.stream().allMatch(token -> token.getDeviceId() != null && token.getTokenId() != null));
        assertTrue(tokens.stream().allMatch(token -> {
            Device device = deviceManager.getDevice(token.getDeviceId());
            return device != null && device.getDeviceGroupId().equals(token.getChannel());
        }));
        assertTrue(tokens.stream().allMatch(token -> {
            Device device = deviceManager.getDevice(token.getDeviceId());
            return device != null && device.getDeviceGroupId().equals(token.getAttributes().get("country"));
        }));
    }

    private JsonObject buildMockConfigRoot() {
        String json = """
                {
                  "devices": [
                    {
                      "MODEL": "Device",
                      "COUNT": 1,
                      "FIELDS": {
                        "deviceId": "device-us-1",
                        "deviceGroupId": "US",
                        "agentVersion": "1.0.0",
                        "status": "ONLINE",
                        "supportedProjects": ["demoApp", "testApp"]
                      }
                    },
                    {
                      "MODEL": "Device",
                      "COUNT": 1,
                      "FIELDS": {
                        "deviceId": "device-gb-1",
                        "deviceGroupId": "GB",
                        "agentVersion": "1.0.1",
                        "status": "ONLINE",
                        "supportedProjects": ["demoApp", "testApp"]
                      }
                    }
                  ],
                  "tasks": [
                    {
                      "MODEL": "TaskCreateRequestDto",
                      "COUNT": 1,
                      "FIELDS": {
                        "taskName": "task-1",
                        "project": "demoApp",
                        "countryCode": "us",
                        "userId": "agent",
                        "textContent": "smoke",
                        "batchSize": 1,
                        "targetList": ["target-1"]
                      }
                    }
                  ]
                }
                """;
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
