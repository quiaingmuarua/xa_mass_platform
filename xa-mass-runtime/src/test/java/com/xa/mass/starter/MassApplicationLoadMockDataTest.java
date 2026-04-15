package com.xa.mass.starter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MassApplicationLoadMockDataTest {

    @Test
    void loadMockDataUsesExplicitTokensWithoutDerivingRoutingSignalsFromDeviceGroup() {
        TestHarness harness = createHarness();
        MassApplication application = new MassApplication(
                harness.engine(), 8088, "/ws", new GatewayConfig(), explicitTokenConfig()
        );

        application.loadMockData(harness.engine(), explicitTokenConfig());

        List<Device> devices = harness.deviceManager().getAllDevices();
        List<Token> tokens = harness.deviceManager().getAllTokens();

        assertEquals(2, devices.size());
        assertEquals(2, tokens.size());
        assertEquals(1, harness.createdTasks().get());
        assertTrue(devices.stream().allMatch(device -> device.supportsProject("demoApp")));
        assertTrue(devices.stream().allMatch(device -> device.supportsProject("testApp")));

        Token usToken = harness.deviceManager().getToken("device-us-1");
        Token gbToken = harness.deviceManager().getToken("device-gb-1");
        assertNotNull(usToken);
        assertNotNull(gbToken);
        assertEquals("route-us", usToken.getChannel());
        assertEquals("route-gb", gbToken.getChannel());
        assertEquals("us", usToken.getAttributes().get("country"));
        assertEquals("gb", gbToken.getAttributes().get("country"));
        assertEquals(TokenStatus.IDLE, usToken.getStatus());
        assertEquals(TokenStatus.IDLE, gbToken.getStatus());
    }

    @Test
    void loadMockDataSeedsMinimalTokensWhenExplicitTokenDataIsMissing() {
        TestHarness harness = createHarness();
        MassApplication application = new MassApplication(
                harness.engine(), 8088, "/ws", new GatewayConfig(), fallbackSeedConfig()
        );

        application.loadMockData(harness.engine(), fallbackSeedConfig());

        List<Device> devices = harness.deviceManager().getAllDevices();
        List<Token> tokens = harness.deviceManager().getAllTokens();

        assertEquals(2, devices.size());
        assertEquals(devices.size(), tokens.size());
        assertEquals(0, harness.createdTasks().get());
        assertTrue(tokens.stream().allMatch(token -> token.getStatus() == TokenStatus.IDLE));
        assertTrue(tokens.stream().allMatch(token -> token.getDeviceId() != null && token.getTokenId() != null));
        assertTrue(tokens.stream().allMatch(token -> token.getChannel() == null));
        assertTrue(tokens.stream().allMatch(token -> token.getAttributes().isEmpty()));
        assertNull(harness.deviceManager().getToken("missing-device"));
    }

    private TestHarness createHarness() {
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

        return new TestHarness(engine, deviceManager, createdTasks);
    }

    private EngineConfig explicitTokenConfig() {
        EngineConfig engineConfig = new EngineConfig();
        engineConfig.setMockConfigRoot(JsonParser.parseString("""
                {
                  "devices": [
                    {
                      "MODEL": "Device",
                      "COUNT": 1,
                      "FIELDS": {
                        "deviceId": "device-us-1",
                        "deviceGroupId": "POOL-US",
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
                        "deviceGroupId": "POOL-GB",
                        "agentVersion": "1.0.1",
                        "status": "ONLINE",
                        "supportedProjects": ["demoApp", "testApp"]
                      }
                    }
                  ],
                  "tokens": [
                    {
                      "MODEL": "Token",
                      "COUNT": 1,
                      "FIELDS": {
                        "tokenId": "token-us-1",
                        "deviceId": "device-us-1",
                        "channel": "route-us",
                        "status": "IDLE",
                        "attributes": {
                          "country": "US",
                          "carrier": "tmobile"
                        }
                      }
                    },
                    {
                      "MODEL": "Token",
                      "COUNT": 1,
                      "FIELDS": {
                        "tokenId": "token-gb-1",
                        "deviceId": "device-gb-1",
                        "channel": "route-gb",
                        "status": "IDLE",
                        "attributes": {
                          "country": "GB",
                          "carrier": "o2"
                        }
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
                        "sharedConfig": {"textContent": "smoke"},
                        "batchSize": 1,
                        "targetList": ["target-1"]
                      }
                    }
                  ]
                }
                """).getAsJsonObject());
        return engineConfig;
    }

    private EngineConfig fallbackSeedConfig() {
        EngineConfig engineConfig = new EngineConfig();
        engineConfig.setMockConfigRoot(JsonParser.parseString("""
                {
                  "devices": [
                    {
                      "MODEL": "Device",
                      "COUNT": 1,
                      "FIELDS": {
                        "deviceId": "device-us-1",
                        "deviceGroupId": "POOL-US",
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
                        "deviceGroupId": "POOL-GB",
                        "agentVersion": "1.0.1",
                        "status": "ONLINE",
                        "supportedProjects": ["demoApp", "testApp"]
                      }
                    }
                  ]
                }
                """).getAsJsonObject());
        return engineConfig;
    }

    private record TestHarness(MassEngine engine, DeviceManager deviceManager, AtomicInteger createdTasks) {
    }
}
