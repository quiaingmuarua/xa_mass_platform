package com.xa.mass.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MassPlatformTest {
    @Test
    void builderNormalizesBaseUrlAndKeepsTimeouts() {
        MassPlatform platform = MassPlatform.builder()
                .baseUrl("http://localhost:8088")
                .apiKey("mass_sk_test")
                .connectTimeout(Duration.ofMillis(1234))
                .requestTimeout(Duration.ofMillis(5678))
                .build();

        assertEquals("http://localhost:8088/", platform.baseUri().toString());
        assertEquals(Duration.ofMillis(1234), platform.connectTimeout());
        assertEquals(Duration.ofMillis(5678), platform.requestTimeout());
        assertEquals(Duration.ofMillis(5678), platform.http().requestTimeout());
    }

    @Test
    void builderRequiresAuthToken() {
        MassPlatform.Builder builder = MassPlatform.builder()
                .baseUrl("http://localhost:8088");

        assertThrows(IllegalStateException.class, builder::build);
    }
}
