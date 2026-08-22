package com.xa.mass.server.kernelpacer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.server.kernelredis.XaMassRedisProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

@Tag("runtime-boundary")
class PythonKernelPacerProcessIntegrationTest {

    private static final String CHILD_SOURCE = """
            import argparse
            import json
            import os
            import sys
            import time
            from pathlib import Path

            parser = argparse.ArgumentParser()
            parser.add_argument("--config", required=True, type=Path)
            parser.add_argument("--instance-token", required=True)
            parser.add_argument("--ready-file", required=True, type=Path)
            args = parser.parse_args()

            config = json.loads(args.config.read_text(encoding="utf-8"))
            Path(config["pidFile"]).write_text(str(os.getpid()), encoding="utf-8")
            Path(config["environmentFile"]).write_text(json.dumps({
                "redisUrl": os.environ["XA_MASS_KERNEL_PACER_REDIS_URL"],
                "redisScope": os.environ["XA_MASS_KERNEL_PACER_REDIS_SCOPE"],
            }), encoding="utf-8")
            mode = config["mode"]
            if mode == "exit":
                sys.exit(7)
            if mode != "timeout":
                args.ready_file.parent.mkdir(parents=True, exist_ok=True)
                args.ready_file.write_text(args.instance_token, encoding="utf-8")
            if mode == "force":
                while True:
                    time.sleep(1)
            sys.stdin.buffer.read()
            if args.ready_file.exists():
                if args.ready_file.read_text(encoding="utf-8") == args.instance_token:
                    args.ready_file.unlink()
            """;

    @TempDir
    private Path temporaryDirectory;

    private Path configPath;
    private Path pidFile;
    private Path environmentFile;

    @BeforeEach
    void createControlledPythonModule() throws Exception {
        Path module = temporaryDirectory.resolve(
                "kernel_design/executable_spec/assembly"
        );
        Files.createDirectories(module);
        Files.writeString(
                temporaryDirectory.resolve("kernel_design/__init__.py"),
                "",
                StandardCharsets.UTF_8
        );
        Files.writeString(
                temporaryDirectory.resolve(
                        "kernel_design/executable_spec/__init__.py"
                ),
                "",
                StandardCharsets.UTF_8
        );
        Files.writeString(
                module.resolve("__init__.py"),
                "",
                StandardCharsets.UTF_8
        );
        Files.writeString(
                module.resolve("__main__.py"),
                CHILD_SOURCE,
                StandardCharsets.UTF_8
        );
        configPath = temporaryDirectory.resolve("pacer.json");
        pidFile = temporaryDirectory.resolve("child.pid");
        environmentFile = temporaryDirectory.resolve("child-environment.json");
    }

    @Test
    void exactReadyTokenAndStdinEofOwnNormalLifecycle() throws Exception {
        writeConfig("normal");
        PythonKernelPacerProcess owner = owner(
                Duration.ofSeconds(5),
                Duration.ofSeconds(2)
        );

        owner.start();
        long pid = readPid();
        assertThat(owner.isAlive()).isTrue();
        assertThat(owner.pid()).isEqualTo(pid);
        assertThat(Files.readString(environmentFile, StandardCharsets.UTF_8))
                .contains("\"redisUrl\": \"redis://example:6380/3\"")
                .contains("\"redisScope\": \"profile_managed\"");

        owner.stop();
        owner.stop();

        assertProcessExited(pid);
        assertStateFilesAbsent();
    }

    @Test
    void childExitBeforeReadyFailsAndCleansOwnedState() throws Exception {
        writeConfig("exit");
        PythonKernelPacerProcess owner = owner(
                Duration.ofSeconds(5),
                Duration.ofSeconds(1)
        );

        assertThatThrownBy(owner::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("child exited with code 7");

        assertProcessExited(readPid());
        assertStateFilesAbsent();
    }

    @Test
    void readinessTimeoutKillsChildAndCleansOwnedState() throws Exception {
        writeConfig("timeout");
        PythonKernelPacerProcess owner = owner(
                Duration.ofMillis(300),
                Duration.ofMillis(200)
        );

        assertThatThrownBy(owner::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("readiness timed out");

        assertProcessExited(readPid());
        assertStateFilesAbsent();
    }

    @Test
    void shutdownTimeoutForciblyKillsChildAndCleansOwnedState()
            throws Exception {
        writeConfig("force");
        PythonKernelPacerProcess owner = owner(
                Duration.ofSeconds(5),
                Duration.ofMillis(200)
        );
        owner.start();
        long pid = readPid();

        owner.stop();

        assertProcessExited(pid);
        assertStateFilesAbsent();
    }

    private PythonKernelPacerProcess owner(
            Duration startupTimeout,
            Duration shutdownTimeout
    ) {
        String executable = System.getenv().getOrDefault(
                "XA_MASS_PYTHON_EXECUTABLE",
                "python"
        );
        return new PythonKernelPacerProcess(
                new KernelPacerProperties(
                        true,
                        executable,
                        temporaryDirectory.toString(),
                        configPath.toString(),
                        "state",
                        startupTimeout,
                        shutdownTimeout
                ),
                new XaMassRedisProperties(
                        URI.create("redis://example:6380/3"),
                        "profile_managed"
                ),
                JsonMapper.builder().build()
        );
    }

    private void writeConfig(String mode) throws Exception {
        Files.writeString(
                configPath,
                "{\"mode\":\"" + mode + "\",\"pidFile\":\""
                        + escapedJson(pidFile.toString())
                        + "\",\"environmentFile\":\""
                        + escapedJson(environmentFile.toString()) + "\"}",
                StandardCharsets.UTF_8
        );
    }

    private long readPid() throws Exception {
        return Long.parseLong(Files.readString(
                pidFile,
                StandardCharsets.UTF_8
        ));
    }

    private void assertProcessExited(long pid) throws Exception {
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        if (handle != null && handle.isAlive()) {
            handle.onExit().get(2, TimeUnit.SECONDS);
        }
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive))
                .isNotEqualTo(java.util.Optional.of(true));
    }

    private void assertStateFilesAbsent() {
        assertThat(temporaryDirectory.resolve("state/ready")).doesNotExist();
        assertThat(temporaryDirectory.resolve("state/owner.json"))
                .doesNotExist();
    }

    private static String escapedJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
