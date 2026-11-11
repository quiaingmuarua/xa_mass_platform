package com.xa.mass.android.workerdemo;

import static org.junit.Assert.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Test;

public class AndroidConnectionSettingsTest {
    @Test public void defaultsRetainTheExistingUnauthenticatedAddress() {
        URI address = URI.create("http://127.0.0.1:18182");
        AndroidConnectionSettings settings = AndroidConnectionSettings.defaults(address);
        assertEquals(address, settings.runtimeApiBaseUrl());
        assertFalse(settings.hasGatewayCredential());
        assertNull(settings.keyId());
        assertNull(settings.connectionOptions().gatewayCredential());
    }

    @Test public void authenticatedSettingsTrimOnlyAddressAndPreserveSecret() {
        String secret = "  密钥 value  ";
        AndroidConnectionSettings settings = AndroidConnectionSettings.authenticated(
                "  https://workers.example:443  ", "host-a", secret);
        assertEquals(URI.create("https://workers.example:443"), settings.runtimeApiBaseUrl());
        assertEquals("host-a", settings.keyId());
        assertTrue(settings.hasGatewayCredential());
        String header = settings.connectionOptions().gatewayCredential().authorizationHeader();
        assertEquals("host-a:" + secret, new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8));
        assertTrue(settings.sameConnection(AndroidConnectionSettings.decodeFromStorage(settings.encodeForStorage())));
        assertFalse(settings.toString().contains(secret));
        assertFalse(settings.toString().contains(header));
    }

    @Test public void validationReusesTheSdkSecureOriginAndCredentialRules() {
        for (String address : new String[]{"http://workers.example", "wss://workers.example", "https://user:password@workers.example", "https://workers.example?token=hidden", "https://workers.example#fragment", "https://workers.example:0"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> AndroidConnectionSettings.authenticated(address, "key", "secret"));
            assertFalse(error.toString().contains("password"));
            assertFalse(error.toString().contains("hidden"));
        }
        assertThrows(IllegalArgumentException.class, () -> AndroidConnectionSettings.authenticated("https://workers.example", " key ", "secret"));
        assertThrows(IllegalArgumentException.class, () -> AndroidConnectionSettings.authenticated("https://workers.example", "key", ""));
        assertThrows(IllegalArgumentException.class, () -> AndroidConnectionSettings.authenticated("https://workers.example", "key", "secret\n"));
    }

    @Test public void malformedAddressAndStoredJsonNeverLeakInputThroughExceptionCauses() {
        String secret = "do-not-display-this";
        IllegalArgumentException address = assertThrows(IllegalArgumentException.class,
                () -> AndroidConnectionSettings.authenticated("https://" + secret + " host", "key", "value"));
        assertNull(address.getCause());
        assertFalse(address.toString().contains(secret));
        IllegalArgumentException payload = assertThrows(IllegalArgumentException.class,
                () -> AndroidConnectionSettings.decodeFromStorage(("{\"secret\":\"" + secret).getBytes(StandardCharsets.UTF_8)));
        assertNull(payload.getCause());
        assertFalse(payload.toString().contains(secret));
        assertThrows(IllegalArgumentException.class,
                () -> AndroidConnectionSettings.decodeFromStorage(new byte[]{(byte) 0xc3, 0x28}));
    }

    @Test public void storedFieldsMustBeCompleteAndHaveStringTypes() {
        for (String json : new String[]{"{}", "{\"runtimeApiBaseUrl\":\"https://workers.example\",\"keyId\":\"key\",\"secret\":null}",
                "{\"runtimeApiBaseUrl\":\"https://workers.example\",\"keyId\":\"key\",\"secret\":\"secret\",\"extra\":true}"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> AndroidConnectionSettings.decodeFromStorage(json.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test public void comparisonDetectsCredentialRotationWithoutExposingTheCredential() {
        AndroidConnectionSettings first = AndroidConnectionSettings.authenticated("https://one.example", "host", "first");
        assertTrue(first.sameConnection(AndroidConnectionSettings.authenticated("https://one.example", "host", "first")));
        assertFalse(first.sameConnection(AndroidConnectionSettings.authenticated("https://one.example", "host", "second")));
        assertFalse(first.sameConnection(AndroidConnectionSettings.authenticated("https://one.example", "other", "first")));
        assertFalse(first.sameConnection(AndroidConnectionSettings.authenticated("https://two.example", "host", "first")));
        assertFalse(first.sameConnection(AndroidConnectionSettings.defaults(URI.create("https://one.example"))));
        assertFalse(first.sameConnection(null));
    }
}
