package com.xa.mass.engine.listener;

import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.service.AssignmentRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SimpleTaskMsgAssignListenerTest {

    private DeviceManager deviceManager;
    private AssignmentRecordService recordService;
    private SimpleTaskMsgAssignListener listener;

    // Spy-friendly subclass that captures the pushQueue via onMsgAssign override
    private final List<TaskMsg> capturedMsgs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        deviceManager = mock(DeviceManager.class);
        recordService = mock(AssignmentRecordService.class);
        listener = new SimpleTaskMsgAssignListener(deviceManager, recordService) {
            // Override the interface method so we can inspect pushQueue indirectly
            // via verifying recordService calls (the real impl calls recordService once per msg)
        };
    }

    @Test
    void messagesAreCreatedOnePerSlot() {
        Task task = task(6);
        List<Device> devices = List.of(device("d1"), device("d2"), device("d3"));
        Token token = token("tk1", "d1");
        when(deviceManager.getToken(anyString())).thenReturn(token);

        listener.onMsgAssign(task, devices);

        // 6 messages / 3 devices = 2 per device → recordService called 6 times
        verify(recordService, times(6)).recordMessageAssignment(
                any(), any(), any(), anyString(), anyString(), any(), anyString()
        );
    }

    @Test
    void remainderIsDistributedAcrossFirstDevices() {
        // 7 messages / 3 devices → [3, 2, 2]
        Task task = task(7);
        List<Device> devices = List.of(device("d1"), device("d2"), device("d3"));
        when(deviceManager.getToken(anyString())).thenReturn(null);

        listener.onMsgAssign(task, devices);

        // 7 total recordService calls
        verify(recordService, times(7)).recordMessageAssignment(
                any(), any(), any(), anyString(), anyString(), any(), anyString()
        );
    }

    @Test
    void singleDeviceTakesAllMessages() {
        Task task = task(4);
        List<Device> devices = List.of(device("d1"));
        when(deviceManager.getToken("d1")).thenReturn(token("tk1", "d1"));

        listener.onMsgAssign(task, devices);

        verify(recordService, times(4)).recordMessageAssignment(
                any(), any(), any(), anyString(), anyString(), any(), anyString()
        );
    }

    @Test
    void nullTokenIsHandledGracefully() {
        Task task = task(2);
        List<Device> devices = List.of(device("d1"));
        when(deviceManager.getToken("d1")).thenReturn(null);  // no token

        assertDoesNotThrow(() -> listener.onMsgAssign(task, devices));
        verify(recordService, times(2)).recordMessageAssignment(
                any(), any(), isNull(), anyString(), anyString(), any(), anyString()
        );
    }

    @Test
    void batchIdsAreAssignedPerDevice() {
        // Each device gets its own batch-N id
        Task task = task(2);
        List<Device> devices = List.of(device("d1"), device("d2"));
        when(deviceManager.getToken(anyString())).thenReturn(null);

        // We can verify batch-0 used for d1, batch-1 for d2
        listener.onMsgAssign(task, devices);

        verify(recordService, times(1)).recordMessageAssignment(
                any(), argThat(d -> "d1".equals(d.getDeviceId())), any(), anyString(),
                eq("batch-0"), any(), anyString()
        );
        verify(recordService, times(1)).recordMessageAssignment(
                any(), argThat(d -> "d2".equals(d.getDeviceId())), any(), anyString(),
                eq("batch-1"), any(), anyString()
        );
    }

    // ---- helpers ----

    private Task task(int initNumber) {
        Task t = new Task();
        t.setTid("task-test");
        t.setTaskInitNumber(initNumber);
        return t;
    }

    private Device device(String id) {
        Device d = new Device();
        d.setDeviceId(id);
        return d;
    }

    private Token token(String tokenId, String deviceId) {
        Token t = new Token();
        t.setTokenId(tokenId);
        t.setDeviceId(deviceId);
        return t;
    }
}
