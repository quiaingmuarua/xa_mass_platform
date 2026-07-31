package com.xa.mass.scenarioworkers;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ScenarioWorkerBundleConfigTest {

    @Test
    void acceptsBoundedWebSocketConfiguration() {
        config(
                URI.create("wss://worker.example.test/connect"),
                100,
                Duration.ofSeconds(1)
        );
    }

    @Test
    void rejectsInvalidIdentityUriCountAndDuration() {
        assertThatThrownBy(() -> new ScenarioWorkerBundleConfig(
                "",
                "scenario-websocket",
                URI.create("ws://127.0.0.1/connect"),
                "workers",
                "worker-",
                1,
                Duration.ofSeconds(1),
                Duration.ofMillis(250),
                Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> config(
                URI.create("http://127.0.0.1/connect"),
                1,
                Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> config(
                URI.create("ws://127.0.0.1/connect"),
                101,
                Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> config(
                URI.create("ws://127.0.0.1/connect"),
                1,
                Duration.ZERO
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static ScenarioWorkerBundleConfig config(
            URI uri,
            int workerCount,
            Duration connectTimeout
    ) {
        return new ScenarioWorkerBundleConfig(
                "phone-number",
                "scenario-websocket",
                uri,
                "scenario-phone-number-workers",
                "scenario-phone-number-worker-",
                workerCount,
                Duration.ofSeconds(10),
                Duration.ofMillis(250),
                connectTimeout
        );
    }
}
