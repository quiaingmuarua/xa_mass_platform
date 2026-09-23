package com.xa.mass.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WorkerConnectionOptionsTest {

    @Test
    void defaultsAreStableAndShared() {
        WorkerConnectionOptions options =
                WorkerConnectionOptions.defaults();

        assertSame(options, WorkerConnectionOptions.defaults());
        assertEquals(Duration.ofSeconds(10), options.requestTimeout());
        assertSame(
                TextMessageReconnectPolicy.defaults(),
                options.reconnectPolicy()
        );
    }

    @Test
    void explicitValuesAreRetained() {
        TextMessageReconnectPolicy reconnectPolicy =
                TextMessageReconnectPolicy.of(
                        3,
                        Duration.ofMillis(25),
                        Duration.ofSeconds(2)
                );

        WorkerConnectionOptions options = WorkerConnectionOptions.of(
                Duration.ofSeconds(4),
                reconnectPolicy
        );

        assertEquals(Duration.ofSeconds(4), options.requestTimeout());
        assertSame(reconnectPolicy, options.reconnectPolicy());
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(
                NullPointerException.class,
                () -> WorkerConnectionOptions.of(
                        null,
                        TextMessageReconnectPolicy.defaults()
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkerConnectionOptions.of(
                        Duration.ZERO,
                        TextMessageReconnectPolicy.defaults()
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> WorkerConnectionOptions.of(
                        Duration.ofSeconds(1),
                        null
                )
        );
    }
}
