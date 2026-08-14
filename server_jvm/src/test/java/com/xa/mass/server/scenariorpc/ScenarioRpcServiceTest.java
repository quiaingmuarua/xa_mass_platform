package com.xa.mass.server.scenariorpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.scenariorpc.ScenarioRpcCall;
import com.xa.mass.scenariorpc.ScenarioRpcEngine;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcRunRequest;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcRunResponse;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioRpcServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void runsOneBuiltInScenarioAndPublishesOrderedOutput()
            throws Exception {
        ScenarioRpcFileStore files = files();
        files.upload(
                "strings.txt",
                "hello\nworld".getBytes(StandardCharsets.UTF_8)
        );
        ScenarioRpcService service = service(
                files,
                (group, message, event, payload) -> Map.of(
                        "valid", true,
                        "md5", "hash-" + payload.get("value")
                )
        );

        ScenarioRpcRunResponse response = service.run(
                new ScenarioRpcRunRequest(
                        "string.md5",
                        "strings.txt",
                        2
                )
        );

        assertThat(response.scenarioId()).isEqualTo("string.md5");
        assertThat(response.inputCount()).isEqualTo(2);
        assertThat(response.resultCount()).isEqualTo(2);
        assertThat(response.outputFile())
                .isEqualTo("string.md5-1786680000123.jsonl");
        String output = new String(
                service.download(response.outputFile()),
                StandardCharsets.UTF_8
        );
        assertThat(output.indexOf("hash-hello"))
                .isLessThan(output.indexOf("hash-world"));
    }

    @Test
    void permitsOnlyOneGlobalRunAndReleasesAfterCompletion()
            throws Exception {
        ScenarioRpcFileStore files = files();
        files.upload("strings.txt", "hello".getBytes(StandardCharsets.UTF_8));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ScenarioRpcService service = service(
                files,
                (group, message, event, payload) -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("timed out");
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                    return Map.of("valid", true, "md5", "hash");
                }
        );
        ScenarioRpcRunRequest request = new ScenarioRpcRunRequest(
                "string.md5",
                "strings.txt",
                1
        );

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.run(request));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.run(request))
                    .isInstanceOfSatisfying(ServerException.class, error ->
                            assertThat(error.errorCode()).isEqualTo(
                                    ServerErrorCode.SCENARIO_RPC_CONFLICT
                            )
                    );
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).resultCount())
                    .isEqualTo(1);
        }
    }

    @Test
    void invalidResultDoesNotPublishOutputAndReleasesRunGuard()
            throws Exception {
        ScenarioRpcFileStore files = files();
        files.upload("strings.txt", "hello".getBytes(StandardCharsets.UTF_8));
        ScenarioRpcService service = service(
                files,
                (group, message, event, payload) -> Map.of("valid", true)
        );
        ScenarioRpcRunRequest request = new ScenarioRpcRunRequest(
                "string.md5",
                "strings.txt",
                1
        );

        assertThatThrownBy(() -> service.run(request))
                .isInstanceOfSatisfying(ServerException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(
                                ServerErrorCode.SCENARIO_RPC_UNAVAILABLE
                        )
                );
        assertThatThrownBy(() -> service.run(request))
                .isInstanceOf(ServerException.class);
    }

    @Test
    void rejectsInvalidRunCoordinatesWithScenarioErrorCode()
            throws Exception {
        ScenarioRpcService service = service(
                files(),
                (group, message, event, payload) -> Map.of()
        );

        assertThatThrownBy(() -> service.run(
                new ScenarioRpcRunRequest("string.md5", "strings.txt", 0)
        )).isInstanceOfSatisfying(ServerException.class, error ->
                assertThat(error.errorCode()).isEqualTo(
                        ServerErrorCode.SCENARIO_RPC_INVALID_REQUEST
                )
        );
    }

    private ScenarioRpcService service(
            ScenarioRpcFileStore files,
            ScenarioRpcCall rpc
    ) {
        return new ScenarioRpcService(
                ScenarioRpcEngine.create(),
                files,
                rpc,
                Clock.fixed(
                        Instant.ofEpochMilli(1_786_680_000_123L),
                        ZoneOffset.UTC
                )
        );
    }

    private ScenarioRpcFileStore files() throws Exception {
        Path root = temporaryDirectory.resolve("data").resolve("rpc-task");
        return ScenarioRpcFileStore.open(new ScenarioRpcProperties(
                root.toString(),
                URI.create("http://127.0.0.1:18082"),
                30_000,
                35_000,
                1024,
                10
        ));
    }
}
