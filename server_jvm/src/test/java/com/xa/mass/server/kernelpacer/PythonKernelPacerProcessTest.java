package com.xa.mass.server.kernelpacer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class PythonKernelPacerProcessTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void historicalProcessIsKilledOnlyWhenArgumentsAreObservable()
            throws Exception {
        String token = "owned-instance";
        Process historical = startHistoricalChild(token);
        try {
            writeOwner(historical, token, token);
            PythonKernelPacerProcess owner = owner();

            if (historical.info().arguments().isPresent()) {
                owner.stopVerifiedHistoricalProcess();

                assertThat(historical.waitFor(2, TimeUnit.SECONDS)).isTrue();
                assertThat(historical.isAlive()).isFalse();
                assertThat(temporaryDirectory.resolve("state/owner.json"))
                        .doesNotExist();
            } else {
                assertThatThrownBy(owner::stopVerifiedHistoricalProcess)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("cannot be verified");
                assertThat(historical.isAlive()).isTrue();
            }
        } finally {
            historical.destroyForcibly();
            historical.waitFor(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void mismatchedTokenNeverKillsTheLiveProcess() throws Exception {
        String actualToken = "actual-instance";
        Process historical = startHistoricalChild(actualToken);
        try {
            writeOwner(historical, "different-instance", actualToken);
            PythonKernelPacerProcess owner = owner();

            assertThatThrownBy(owner::stopVerifiedHistoricalProcess)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot be verified");
            assertThat(historical.isAlive()).isTrue();
        } finally {
            historical.destroyForcibly();
            historical.waitFor(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void missingFixedModuleArgumentsNeverKillsTheLiveProcess()
            throws Exception {
        String token = "owned-instance";
        Process historical = new ProcessBuilder(
                javaExecutable().toString(),
                "-cp",
                System.getProperty("java.class.path"),
                KernelPacerHistoricalChild.class.getName()
        ).start();
        try {
            writeOwner(historical, token, token);
            PythonKernelPacerProcess owner = owner();

            assertThatThrownBy(owner::stopVerifiedHistoricalProcess)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot be verified");
            assertThat(historical.isAlive()).isTrue();
        } finally {
            historical.destroyForcibly();
            historical.waitFor(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void missingConfigFailsBeforeStartingAChild() {
        PythonKernelPacerProcess owner = new PythonKernelPacerProcess(
                new KernelPacerProperties(
                        true,
                        javaExecutable().toString(),
                        temporaryDirectory.toString(),
                        "missing.json",
                        "state",
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(1)
                ),
                JsonMapper.builder().build()
        );

        assertThatThrownBy(owner::start)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("configPath");
        assertThat(owner.isAlive()).isFalse();
        assertThat(temporaryDirectory.resolve("state/owner.json"))
                .doesNotExist();
    }

    private PythonKernelPacerProcess owner() throws Exception {
        Path config = temporaryDirectory.resolve("kernel.json");
        Files.writeString(config, "{}", StandardCharsets.UTF_8);
        return new PythonKernelPacerProcess(
                new KernelPacerProperties(
                        true,
                        javaExecutable().toString(),
                        temporaryDirectory.toString(),
                        config.toString(),
                        "state",
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(1)
                ),
                JsonMapper.builder().build()
        );
    }

    private Process startHistoricalChild(String token) throws Exception {
        List<String> command = List.of(
                javaExecutable().toString(),
                "-cp",
                System.getProperty("java.class.path"),
                KernelPacerHistoricalChild.class.getName(),
                "-m",
                PythonKernelPacerProcess.MODULE,
                "--instance-token",
                token
        );
        Process process = new ProcessBuilder(command).start();
        assertThat(process.isAlive()).isTrue();
        return process;
    }

    private void writeOwner(
            Process historical,
            String ownerToken,
            String readyToken
    )
            throws Exception {
        Instant start = historical.info().startInstant().orElseThrow();
        Path state = temporaryDirectory.resolve("state");
        Files.createDirectories(state);
        String json = "{"
                + "\"pid\":" + historical.pid() + ","
                + "\"processStartInstant\":\"" + start + "\","
                + "\"instanceToken\":\"" + ownerToken + "\","
                + "\"configPath\":\"kernel.json\","
                + "\"module\":\""
                + PythonKernelPacerProcess.MODULE + "\","
                + "\"executableCommand\":\""
                + escapedJson(historical.info().command().orElseThrow())
                + "\""
                + "}";
        Files.writeString(
                state.resolve("owner.json"),
                json,
                StandardCharsets.UTF_8
        );
        Files.writeString(
                state.resolve("ready"),
                readyToken,
                StandardCharsets.UTF_8
        );
    }

    private static Path javaExecutable() {
        return Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java"
        );
    }

    private static String escapedJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
