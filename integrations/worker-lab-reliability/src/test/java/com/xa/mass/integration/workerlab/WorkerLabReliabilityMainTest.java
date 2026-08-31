package com.xa.mass.integration.workerlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorkerLabReliabilityMainTest {

    @Test
    void parsesDefaultsAndStrictOverrides() {
        WorkerLabReliabilityMain.Options defaults =
                WorkerLabReliabilityMain.Options.parse(new String[0]);

        assertThat(defaults.runtimeApiBaseUrl())
                .isEqualTo(URI.create("http://127.0.0.1:18082"));
        assertThat(defaults.labControlBaseUrl())
                .isEqualTo(URI.create("http://127.0.0.1:18086"));
        assertThat(defaults.endpointManagerId())
                .isEqualTo("scenario-websocket");
        assertThat(defaults.maximumWaitMillis()).isEqualTo(120_000);
        assertThat(defaults.requestTimeoutMillis()).isEqualTo(10_000);
        assertThat(defaults.scheduledStopDelayMillis()).isEqualTo(1_000);

        WorkerLabReliabilityMain.Options configured =
                WorkerLabReliabilityMain.Options.parse(new String[]{
                        "--runtime-api-base-url=http://localhost:19082",
                        "--lab-control-base-url=http://localhost:19086",
                        "--endpoint-manager-id=adapter-1",
                        "--proof-id=proof-1",
                        "--evidence-dir=proof-output",
                        "--maximum-wait-millis=5000",
                        "--request-timeout-millis=2000",
                        "--scheduled-stop-delay-millis=50"
                });

        assertThat(configured.proofId()).isEqualTo("proof-1");
        assertThat(configured.evidenceDirectory())
                .isEqualTo(Path.of("proof-output"));
        assertThat(configured.maximumWaitMillis()).isEqualTo(5_000);
        assertThat(configured.requestTimeoutMillis()).isEqualTo(2_000);
        assertThat(configured.scheduledStopDelayMillis()).isEqualTo(50);
    }

    @Test
    void rejectsUnknownDuplicateAndOutOfRangeArguments() {
        assertThatThrownBy(() -> WorkerLabReliabilityMain.Options.parse(
                new String[]{"--unknown=value"}
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown");

        assertThatThrownBy(() -> WorkerLabReliabilityMain.Options.parse(
                new String[]{"--proof-id=one", "--proof-id=two"}
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");

        assertThatThrownBy(() -> WorkerLabReliabilityMain.Options.parse(
                new String[]{"--maximum-wait-millis=999"}
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum-wait-millis");
    }
}
