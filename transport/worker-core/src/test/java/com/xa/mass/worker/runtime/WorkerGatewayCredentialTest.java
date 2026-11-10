package com.xa.mass.worker.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class WorkerGatewayCredentialTest {
    private final WorkerGatewayCredential credential = WorkerGatewayCredential.of("host-1", "a-private-secret");

    @Test
    void immutableOptionsKeepDefaultsAndRedactTheSecret() {
        WorkerConnectionOptions defaults = WorkerConnectionOptions.defaults();
        WorkerConnectionOptions configured = defaults.withGatewayCredential(credential);
        assertNull(defaults.gatewayCredential());
        assertSame(credential, configured.gatewayCredential());
        assertEquals(defaults.requestTimeout(), configured.requestTimeout());
        assertSame(defaults.reconnectPolicy(), configured.reconnectPolicy());
        assertEquals("host-1:a-private-secret", new String(Base64.getDecoder().decode(
                credential.authorizationHeader().substring(6)), StandardCharsets.UTF_8));
        assertFalse(credential.toString().contains("a-private-secret"));
        assertFalse(credential.toString().contains(credential.authorizationHeader()));
        assertTrue(credential.toString().contains("<redacted>"));
    }

    @Test
    void originsUseSecureSchemesAndEffectivePorts() {
        URI runtime = URI.create("https://WORKERS.example/api-base");
        credential.validateRuntimeUri(runtime);
        credential.validateEndpointUri(runtime, URI.create("wss://workers.example:443/socket"));
        for (String value : new String[]{"ws://workers.example/socket", "wss://other.example/socket",
                "wss://workers.example:444/socket", "wss://user@workers.example/socket",
                "wss://workers.example/socket?secret=value", "wss://workers.example/socket#fragment"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> credential.validateEndpointUri(runtime, URI.create(value)));
        }
        for (String value : new String[]{"http://workers.example", "https://user:secret@workers.example",
                "https://workers.example?secret=value", "https://workers.example#fragment",
                "https://workers.example:0", "https://workers.example:65536", "https:///missing-host"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> credential.validateRuntimeUri(URI.create(value)));
        }
    }

    @Test
    void invalidCredentialsNeverAppearInValidationErrors() {
        for (String key : new String[]{"", "a:b", "a\r\nb", "非ASCII", "a b", "a".repeat(129)}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> WorkerGatewayCredential.of(key, "private-secret"));
            assertFalse(error.getMessage().contains("private-secret"));
        }
        for (String secret : new String[]{"", " ", "a\rb", "a\nb", "a\tb", "a".repeat(4097)}) {
            assertThrows(IllegalArgumentException.class, () -> WorkerGatewayCredential.of("host", secret));
        }
    }
}
