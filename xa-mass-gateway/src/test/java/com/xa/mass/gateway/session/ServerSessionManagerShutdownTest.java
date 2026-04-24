package com.xa.mass.gateway.session;

import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ServerSessionManagerShutdownTest {

    private ServerSessionManager manager;

    @BeforeEach
    void setUp() {
        manager = new ServerSessionManager();
    }

    @Test
    void shutdownClosesAllActiveChannelsAndClearsState() {
        Channel ch1 = mockActiveChannel("ch1");
        Channel ch2 = mockActiveChannel("ch2");
        ChannelHandlerContext ctx1 = mock(ChannelHandlerContext.class);
        ChannelHandlerContext ctx2 = mock(ChannelHandlerContext.class);

        manager.addSession("worker-1", SessionRoles.TASK_MESSAGES, ch1, ctx1);
        manager.addSession("worker-2", SessionRoles.TASK_MESSAGES, ch2, ctx2);

        assertEquals(2, manager.getWorkerConnectionCount());

        manager.shutdown();

        verify(ch1).close();
        verify(ch2).close();
        // Internal maps cleared: no more connections reported
        assertEquals(0, manager.getAllWorkerChannels().size());
    }

    @Test
    void shutdownOnEmptyManagerIsIdempotent() {
        assertDoesNotThrow(() -> manager.shutdown());
    }

    @Test
    void workerOnlineOfflineSignalsTrackWorkerLevelReachability() {
        WorkerSystemEventChannel systemEventChannel = mock(WorkerSystemEventChannel.class);
        manager.setSystemEventChannel(systemEventChannel);
        Channel taskChannel = mockActiveChannel("task-1");
        Channel controlChannel = mockActiveChannel("control-1");
        ChannelHandlerContext taskCtx = mock(ChannelHandlerContext.class);
        ChannelHandlerContext controlCtx = mock(ChannelHandlerContext.class);

        manager.addSession("worker-1", SessionRoles.TASK_MESSAGES, taskChannel, taskCtx);
        manager.addSession("worker-1", "control_events", controlChannel, controlCtx);

        verify(systemEventChannel, times(1)).publishWorkerOnline("worker-1", "websocket connected", null);
        assertTrue(manager.isWorkerOnline("worker-1", SessionRoles.TASK_MESSAGES));
        assertTrue(manager.isWorkerOnline("worker-1", "control_events"));

        manager.removeSession(taskChannel);

        verify(systemEventChannel, never()).publishWorkerOffline("worker-1", "websocket disconnected", null);
        assertFalse(manager.isWorkerOnline("worker-1", SessionRoles.TASK_MESSAGES));
        assertTrue(manager.isWorkerOnline("worker-1", "control_events"));

        manager.removeSession(controlChannel);

        verify(systemEventChannel, times(1)).publishWorkerOffline("worker-1", "websocket disconnected", null);
    }

    @Test
    void replacingSameRoleChannelDoesNotFlapWorkerOfflineOnline() {
        WorkerSystemEventChannel systemEventChannel = mock(WorkerSystemEventChannel.class);
        manager.setSystemEventChannel(systemEventChannel);
        Channel firstChannel = mockActiveChannel("task-old");
        Channel secondChannel = mockActiveChannel("task-new");
        ChannelHandlerContext firstCtx = mock(ChannelHandlerContext.class);
        ChannelHandlerContext secondCtx = mock(ChannelHandlerContext.class);

        manager.addSession("worker-1", SessionRoles.TASK_MESSAGES, firstChannel, firstCtx);
        manager.addSession("worker-1", SessionRoles.TASK_MESSAGES, secondChannel, secondCtx);

        verify(systemEventChannel, times(1)).publishWorkerOnline("worker-1", "websocket connected", null);
        verify(systemEventChannel, never()).publishWorkerOffline("worker-1", "websocket disconnected", null);
        assertEquals(secondChannel, manager.getChannel("worker-1", SessionRoles.TASK_MESSAGES));

        manager.removeSession(firstChannel);

        verify(systemEventChannel, never()).publishWorkerOffline("worker-1", "websocket disconnected", null);

        manager.removeSession(secondChannel);

        verify(systemEventChannel, times(1)).publishWorkerOffline("worker-1", "websocket disconnected", null);
    }

    @Test
    void shutdownSkipsInactiveChannels() {
        Channel active = mockActiveChannel("active");
        Channel inactive = mock(Channel.class);
        ChannelId inactiveId = mock(ChannelId.class);
        when(inactive.isActive()).thenReturn(false);
        when(inactive.id()).thenReturn(inactiveId);
        when(inactiveId.asShortText()).thenReturn("inactive");

        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        manager.addSession("worker-a", "role", active, ctx);
        manager.addSession("worker-b", "role", inactive, ctx);

        manager.shutdown();

        verify(active).close();
        verify(inactive, never()).close();
    }

    // ---- helpers ----

    private Channel mockActiveChannel(String idText) {
        Channel ch = mock(Channel.class);
        ChannelId chId = mock(ChannelId.class);
        when(chId.asShortText()).thenReturn(idText);
        when(ch.id()).thenReturn(chId);
        when(ch.isActive()).thenReturn(true);
        return ch;
    }
}
