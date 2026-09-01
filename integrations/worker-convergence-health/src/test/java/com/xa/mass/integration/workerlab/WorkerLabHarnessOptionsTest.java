package com.xa.mass.integration.workerlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WorkerLabHarnessOptionsTest {

    @Test
    void parsesCommonLaneOptionsStrictly() {
        WorkerLabArguments arguments = WorkerLabArguments.parse(
                new String[]{
                        "--proof-id=proof-one",
                        "--maximum-wait-millis=5000"
                },
                WorkerLabHarnessOptions.ARGUMENT_NAMES
        );
        WorkerLabHarnessOptions options = WorkerLabHarnessOptions.from(
                arguments,
                "default-proof"
        );

        assertThat(options.proofId()).isEqualTo("proof-one");
        assertThat(options.maximumWaitMillis()).isEqualTo(5_000);
        assertThat(options.evidenceDirectory().toString())
                .contains("worker-convergence-health");
    }

    @Test
    void rejectsOldMonolithicArguments() {
        assertThatThrownBy(() -> WorkerLabArguments.parse(
                new String[]{"--scheduled-stop-delay-millis=100"},
                WorkerLabHarnessOptions.ARGUMENT_NAMES
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown Worker Lab argument");
    }
}
