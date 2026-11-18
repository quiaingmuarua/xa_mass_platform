package com.xa.mass.worker.javase;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.SocketFactory;
import jdk.net.ExtendedSocketOptions;
import org.junit.jupiter.api.Test;

class JavaWebSocketSocketFactoryTest {
    private static final Set<SocketOption<?>> FAST_OPTIONS = Set.of(
            ExtendedSocketOptions.TCP_KEEPIDLE,
            ExtendedSocketOptions.TCP_KEEPINTERVAL,
            ExtendedSocketOptions.TCP_KEEPCOUNT);

    @Test
    void delegatesEveryOverloadAndConfiguresEveryReturnedSocket() throws Exception {
        RecordingFactory delegate = new RecordingFactory();
        SocketFactory factory = wrap(delegate);
        InetAddress address = InetAddress.getLoopbackAddress();
        factory.createSocket();
        factory.createSocket("remote", 443);
        factory.createSocket("remote", 443, address, 1234);
        factory.createSocket(address, 443);
        factory.createSocket(address, 443, address, 1234);

        assertEquals(List.of(List.of(), List.of("remote", 443), List.of("remote", 443, address, 1234),
                List.of(address, 443), List.of(address, 443, address, 1234)), delegate.arguments);
        for (RecordingSocket socket : delegate.sockets) {
            assertTrue(socket.keepAlive);
            assertEquals(Map.of(ExtendedSocketOptions.TCP_KEEPIDLE, 60,
                    ExtendedSocketOptions.TCP_KEEPINTERVAL, 10,
                    ExtendedSocketOptions.TCP_KEEPCOUNT, 3), socket.options);
            assertFalse(socket.closed);
        }
    }

    @Test
    void incompleteFastOptionSupportKeepsAllOperatingSystemDefaults() throws Exception {
        for (Set<SocketOption<?>> supported : List.of(Set.<SocketOption<?>>of(),
                Set.<SocketOption<?>>of(ExtendedSocketOptions.TCP_KEEPIDLE,
                        ExtendedSocketOptions.TCP_KEEPINTERVAL))) {
            RecordingFactory delegate = new RecordingFactory();
            delegate.supported = supported;
            RecordingSocket socket = (RecordingSocket) wrap(delegate).createSocket();
            assertTrue(socket.keepAlive);
            assertTrue(socket.options.isEmpty());
            assertFalse(socket.closed);
        }
    }

    @Test
    void failedStandardOrExtendedOptionClosesTheOriginalSocket() throws Exception {
        for (boolean standard : List.of(true, false)) {
            RecordingFactory delegate = new RecordingFactory();
            IOException failure = standard ? new SocketException("keepalive rejected") : new IOException("option rejected");
            delegate.standardFailure = standard ? (SocketException) failure : null;
            delegate.extendedFailure = standard ? null : failure;
            IOException actual = assertThrows(IOException.class, () -> wrap(delegate).createSocket());
            assertSame(failure, actual);
            assertTrue(delegate.sockets.getFirst().closed);
        }
    }

    @Test
    void failedCapabilityReadClosesTheOriginalSocketAndPreservesCloseFailure() throws Exception {
        RecordingFactory delegate = new RecordingFactory();
        delegate.capabilityFailure = new IllegalStateException("capability failed");
        delegate.closeFailure = new IOException("close failed");
        var failure = assertThrows(IllegalStateException.class, () -> wrap(delegate).createSocket());
        assertSame(delegate.capabilityFailure, failure);
        assertTrue(delegate.sockets.getFirst().closed);
        assertArrayEquals(new Throwable[]{delegate.closeFailure}, failure.getSuppressed());
    }

    @Test
    void nativeSocketReportsTheConfiguredKeepaliveValues() throws Exception {
        try (Socket socket = wrap(SocketFactory.getDefault()).createSocket()) {
            assertFalse(socket.isConnected());
            assertTrue(socket.getKeepAlive());
            if (socket.supportedOptions().containsAll(FAST_OPTIONS)) {
                assertEquals(60, socket.getOption(ExtendedSocketOptions.TCP_KEEPIDLE));
                assertEquals(10, socket.getOption(ExtendedSocketOptions.TCP_KEEPINTERVAL));
                assertEquals(3, socket.getOption(ExtendedSocketOptions.TCP_KEEPCOUNT));
            }
        }
    }

    private static SocketFactory wrap(SocketFactory delegate) throws Exception {
        Class<?> type = Class.forName(JavaWorkerPlatform.class.getName() + "$WebSocketSocketFactory");
        Constructor<?> constructor = type.getDeclaredConstructor(SocketFactory.class);
        constructor.setAccessible(true);
        return (SocketFactory) constructor.newInstance(delegate);
    }

    private static final class RecordingFactory extends SocketFactory {
        final List<List<Object>> arguments = new ArrayList<>();
        final List<RecordingSocket> sockets = new ArrayList<>();
        Set<SocketOption<?>> supported = FAST_OPTIONS;
        SocketException standardFailure;
        IOException extendedFailure;
        RuntimeException capabilityFailure;
        IOException closeFailure;

        private Socket create(Object... values) {
            arguments.add(List.of(values));
            var socket = new RecordingSocket(this);
            sockets.add(socket);
            return socket;
        }

        @Override public Socket createSocket() { return create(); }
        @Override public Socket createSocket(String host, int port) { return create(host, port); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) {
            return create(host, port, local, localPort);
        }
        @Override public Socket createSocket(InetAddress host, int port) { return create(host, port); }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) {
            return create(host, port, local, localPort);
        }
    }

    private static final class RecordingSocket extends Socket {
        final RecordingFactory source;
        final Map<SocketOption<?>, Object> options = new LinkedHashMap<>();
        boolean keepAlive;
        boolean closed;

        RecordingSocket(RecordingFactory source) { this.source = source; }

        @Override public void setKeepAlive(boolean value) throws SocketException {
            if (source.standardFailure != null) throw source.standardFailure;
            keepAlive = value;
        }

        @Override public Set<SocketOption<?>> supportedOptions() {
            if (source.capabilityFailure != null) throw source.capabilityFailure;
            return source.supported;
        }

        @Override public <T> Socket setOption(SocketOption<T> name, T value) throws IOException {
            if (source.extendedFailure != null) throw source.extendedFailure;
            options.put(name, value);
            return this;
        }

        @Override public void close() throws IOException {
            closed = true;
            if (source.closeFailure != null) throw source.closeFailure;
        }
    }
}
