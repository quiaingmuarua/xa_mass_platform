package com.xa.mass.server.kernelpacer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.server.kernelredis.XaMassRedisProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class PythonKernelPacerProcessTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void liveHistoricalProcessBlocksStartupWithoutBeingKilled()
            throws Exception {
        Process historical = startHistoricalChild();
        try {
            writeOwner(
                    historical.pid(),
                    historical.info().startInstant().orElseThrow(),
                    historical.info().command().orElseThrow()
            );
            PythonKernelPacerProcess owner = owner();

            assertThatThrownBy(owner::prepareHistoricalState)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(
                            "existing managed process is still running"
                    );
            assertThat(historical.isAlive()).isTrue();
            assertThat(temporaryDirectory.resolve("state/owner.json"))
                    .exists();

            owner.stop();

            assertThat(historical.isAlive()).isTrue();
            assertThat(temporaryDirectory.resolve("state/owner.json"))
                    .exists();
        } finally {
            historical.destroyForcibly();
            historical.waitFor(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void reusedPidStateIsCleanedWithoutTouchingTheLiveProcess()
            throws Exception {
        Process historical = startHistoricalChild();
        try {
            writeOwner(
                    historical.pid(),
                    Instant.EPOCH,
                    historical.info().command().orElseThrow()
            );
            PythonKernelPacerProcess owner = owner();

            owner.prepareHistoricalState();

            assertThat(historical.isAlive()).isTrue();
            assertStateFilesAbsent();
        } finally {
            historical.destroyForcibly();
            historical.waitFor(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void deadHistoricalStateIsCleaned() throws Exception {
        writeOwner(Long.MAX_VALUE, Instant.EPOCH, "python");

        owner().prepareHistoricalState();

        assertStateFilesAbsent();
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
                redisProperties(),
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
                redisProperties(),
                JsonMapper.builder().build()
        );
    }

    private Process startHistoricalChild() throws Exception {
        Process process = new ProcessBuilder(
                javaExecutable().toString(),
                "-cp",
                System.getProperty("java.class.path"),
                KernelPacerHistoricalChild.class.getName()
        ).start();
        assertThat(process.isAlive()).isTrue();
        return process;
    }

    private void writeOwner(
            long pid,
            Instant processStart,
            String executableCommand
    )
            throws Exception {
        Path state = temporaryDirectory.resolve("state");
        Files.createDirectories(state);
        String json = "{"
                + "\"pid\":" + pid + ","
                + "\"processStartInstant\":\"" + processStart + "\","
                + "\"instanceToken\":\"owned-instance\","
                + "\"configPath\":\"kernel.json\","
                + "\"module\":\""
                + PythonKernelPacerProcess.MODULE + "\","
                + "\"executableCommand\":\""
                + escapedJson(executableCommand)
                + "\""
                + "}";
        Files.writeString(
                state.resolve("owner.json"),
                json,
                StandardCharsets.UTF_8
        );
        Files.writeString(
                state.resolve("ready"),
                "owned-instance",
                StandardCharsets.UTF_8
        );
    }

    private void assertStateFilesAbsent() {
        assertThat(temporaryDirectory.resolve("state/ready")).doesNotExist();
        assertThat(temporaryDirectory.resolve("state/owner.json"))
                .doesNotExist();
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

    private static XaMassRedisProperties redisProperties() {
        return new XaMassRedisProperties(
                URI.create("redis://example:6380/3"),
                "profile_managed"
        );
    }
}
