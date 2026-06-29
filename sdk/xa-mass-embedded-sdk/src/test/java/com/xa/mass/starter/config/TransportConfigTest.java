package com.xa.mass.starter.config;

import com.xa.mass.transport.starter.EmbeddedAdapterDeclaration;
import com.xa.mass.transport.starter.EmbeddedSocketAdapterDeclaration;
import com.xa.mass.transport.starter.EmbeddedWebSocketAdapterDeclaration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportConfigTest {

    @Test
    void isEnabledIgnoresServerOnlyBundledWebSocketAdapterState() {
        TransportConfig config = disabledConfig();
        EmbeddedWebSocketAdapterDeclaration declaration = config.getBundledWebSocketAdapterDeclaration();
        declaration.setServerEnabled(true);
        config.setBundledWebSocketAdapterDeclaration(declaration);

        assertFalse(config.isEnabled());
    }

    @Test
    void isEnabledRecognizesSupplementalBundledAdapterStateOnlyWhenAdapterEnabled() {
        TransportConfig config = disabledConfig();
        EmbeddedSocketAdapterDeclaration extraSocket = new EmbeddedSocketAdapterDeclaration();
        extraSocket.setAdapterId("socket-edge");
        extraSocket.setEnabled(false);
        extraSocket.setServerEnabled(true);
        config.addSupplementalSocketAdapterDeclaration(extraSocket);

        assertFalse(config.isEnabled());

        extraSocket.setEnabled(true);
        config.addSupplementalSocketAdapterDeclaration(extraSocket);

        assertTrue(config.isEnabled());
    }

    @Test
    void buildsAdapterStarterDeclarations() {
        TransportConfig config = disabledConfig();
        EmbeddedWebSocketAdapterDeclaration extraWebSocket = new EmbeddedWebSocketAdapterDeclaration();
        extraWebSocket.setAdapterId("ws-extra");
        extraWebSocket.setEnabled(true);
        extraWebSocket.setServerEnabled(true);
        extraWebSocket.setServerPort(19111);
        extraWebSocket.setEndpointPath("/ws-extra");
        config.addSupplementalWebSocketAdapterDeclaration(extraWebSocket);

        EmbeddedAdapterDeclaration declaration = config.resolveEmbeddedAdapterDeclarations().stream()
                .filter(candidate -> "ws-extra".equals(candidate.adapterId()))
                .findFirst()
                .orElseThrow();

        assertEquals("ws-extra", declaration.adapterId());
        assertEquals("ws-extra", declaration.dispatchQueueKey());
        assertEquals("true", declaration.options().get("serverEnabled"));
        assertEquals("19111", declaration.options().get("serverPort"));
        assertEquals("/ws-extra", declaration.options().get("endpointPath"));
    }

    @Test
    void rejectsDuplicateAdapterIds() {
        TransportConfig config = disabledConfig();
        EmbeddedWebSocketAdapterDeclaration first = new EmbeddedWebSocketAdapterDeclaration();
        first.setAdapterId("edge");
        first.setEnabled(true);
        EmbeddedSocketAdapterDeclaration second = new EmbeddedSocketAdapterDeclaration();
        second.setAdapterId("edge");
        second.setEnabled(true);
        config.addSupplementalWebSocketAdapterDeclaration(first);
        config.addSupplementalSocketAdapterDeclaration(second);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                config::resolveEmbeddedAdapterDeclarations
        );
        assertTrue(error.getMessage().contains("Duplicate transport adapterId configured: edge"));
    }

    private static TransportConfig disabledConfig() {
        TransportConfig config = new TransportConfig();
        EmbeddedWebSocketAdapterDeclaration webSocket = config.getBundledWebSocketAdapterDeclaration();
        webSocket.setEnabled(false);
        webSocket.setServerEnabled(false);
        config.setBundledWebSocketAdapterDeclaration(webSocket);
        EmbeddedSocketAdapterDeclaration socket = config.getBundledSocketAdapterDeclaration();
        socket.setEnabled(false);
        socket.setServerEnabled(false);
        config.setBundledSocketAdapterDeclaration(socket);
        return config;
    }
}
