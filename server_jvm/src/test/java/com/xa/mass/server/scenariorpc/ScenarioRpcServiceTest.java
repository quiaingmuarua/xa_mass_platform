package com.xa.mass.server.scenariorpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.scenariorpc.ScenarioRpcDescriptor;
import com.xa.mass.scenariorpc.ScenarioRpcItem;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcCreateRequest;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcRunRequest;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class ScenarioRpcServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsRunsOnceAndPublishesSuccessfulIncrementalOutput()
            throws Exception {
        ScenarioRpcTaskBatchExchange exchange = successfulExchange();
        ScenarioRpcService service = service(exchange, 100);
        service.upload(
                "strings.txt",
                "hello\nworld".getBytes(StandardCharsets.UTF_8)
        );
        var created = service.create(new ScenarioRpcCreateRequest(
                "string.md5"
        ));

        var response = service.run(
                created.scenarioId(),
                new ScenarioRpcRunRequest("strings.txt", 1L, 3)
        );

        assertThat(created.scenarioId())
                .isEqualTo("scenario-1786680000123");
        assertThat(response.status()).isEqualTo("succeeded");
        assertThat(response.resultCount()).isEqualTo(2);
        assertThat(response.remainingCount()).isZero();
        assertThat(response.outputFile())
                .isEqualTo("scenario-1786680000123.jsonl");
        String output = new String(
                service.download(response.outputFile()),
                StandardCharsets.UTF_8
        );
        assertThat(output)
                .contains("hash-hello")
                .contains("hash-world");
        assertThat(service.get(created.scenarioId()).status())
                .isEqualTo("succeeded");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ScenarioRpcItem>> items =
                ArgumentCaptor.forClass(List.class);
        verify(exchange).append(any(), items.capture());
        assertThat(items.getValue()).hasSize(2);
        assertThatThrownBy(() -> service.run(
                created.scenarioId(),
                new ScenarioRpcRunRequest("strings.txt", 1L, 1)
        )).isInstanceOfSatisfying(ServerException.class, error ->
                assertThat(error.errorCode()).isEqualTo(
                        ServerErrorCode.SCENARIO_RPC_CONFLICT
                )
        );
    }

    @Test
    void publishesPartialAfterMaximumLoadRounds() throws Exception {
        ScenarioRpcTaskBatchExchange exchange = mock(
                ScenarioRpcTaskBatchExchange.class
        );
        doNothing().when(exchange).append(any(), anyList());
        when(exchange.loadResults(any(), anyList())).thenReturn(Map.of());
        ScenarioRpcService service = service(exchange, 100);
        service.upload(
                "strings.txt",
                "hello\nworld".getBytes(StandardCharsets.UTF_8)
        );
        String scenarioId = service.create(new ScenarioRpcCreateRequest(
                "string.md5"
        )).scenarioId();

        var response = service.run(
                scenarioId,
                new ScenarioRpcRunRequest("strings.txt", 1L, 2)
        );

        assertThat(response.status()).isEqualTo("partial");
        assertThat(response.resultCount()).isZero();
        assertThat(response.remainingCount()).isEqualTo(2);
        assertThat(response.loadRounds()).isEqualTo(2);
        assertThat(response.outputFile())
                .isEqualTo(scenarioId + ".partial.jsonl");
        assertThat(service.download(response.outputFile())).isEmpty();
    }

    @Test
    void invalidResultFailsTheInstanceWithoutFormalOutput()
            throws Exception {
        ScenarioRpcTaskBatchExchange exchange = mock(
                ScenarioRpcTaskBatchExchange.class
        );
        doNothing().when(exchange).append(any(), anyList());
        when(exchange.loadResults(any(), anyList())).thenAnswer(invocation -> {
            List<String> pending = invocation.getArgument(1);
            return Map.of(
                    pending.getFirst(),
                    Map.of("valid", true)
            );
        });
        ScenarioRpcService service = service(exchange, 100);
        service.upload(
                "strings.txt",
                "hello".getBytes(StandardCharsets.UTF_8)
        );
        String scenarioId = service.create(new ScenarioRpcCreateRequest(
                "string.md5"
        )).scenarioId();

        assertThatThrownBy(() -> service.run(
                scenarioId,
                new ScenarioRpcRunRequest("strings.txt", 1L, 1)
        )).isInstanceOfSatisfying(ServerException.class, error ->
                assertThat(error.errorCode()).isEqualTo(
                        ServerErrorCode.SCENARIO_RPC_UNAVAILABLE
                )
        );
        assertThat(service.get(scenarioId).status()).isEqualTo("failed");
        assertThatThrownBy(() -> service.download(scenarioId + ".jsonl"))
                .isInstanceOf(ServerException.class);
    }

    @Test
    void permitsOnlyOneRunningScenarioGlobally() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ScenarioRpcTaskBatchExchange exchange = mock(
                ScenarioRpcTaskBatchExchange.class
        );
        doNothing().when(exchange).append(any(), anyList());
        when(exchange.loadResults(any(), anyList())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out");
            }
            List<String> pending = invocation.getArgument(1);
            return successfulResults(pending);
        });
        ScenarioRpcService service = service(exchange, 100);
        service.upload(
                "strings.txt",
                "hello".getBytes(StandardCharsets.UTF_8)
        );
        String firstId = service.create(new ScenarioRpcCreateRequest(
                "string.md5"
        )).scenarioId();
        String secondId = service.create(new ScenarioRpcCreateRequest(
                "string.sha1"
        )).scenarioId();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.run(
                    firstId,
                    new ScenarioRpcRunRequest("strings.txt", 1L, 1)
            ));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.run(
                    secondId,
                    new ScenarioRpcRunRequest("strings.txt", 1L, 1)
            )).isInstanceOfSatisfying(ServerException.class, error ->
                    assertThat(error.errorCode()).isEqualTo(
                            ServerErrorCode.SCENARIO_RPC_CONFLICT
                    )
            );
            assertThat(service.get(secondId).status()).isEqualTo("created");
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).status())
                    .isEqualTo("succeeded");
        }
    }

    @Test
    void evictsTheOldestTerminalInstanceAtTheConfiguredBound()
            throws Exception {
        ScenarioRpcService service = service(successfulExchange(), 1);
        service.upload(
                "strings.txt",
                "hello".getBytes(StandardCharsets.UTF_8)
        );
        String first = service.create(new ScenarioRpcCreateRequest(
                "string.md5"
        )).scenarioId();
        service.run(
                first,
                new ScenarioRpcRunRequest("strings.txt", 1L, 1)
        );

        String second = service.create(new ScenarioRpcCreateRequest(
                "string.sha1"
        )).scenarioId();

        assertThatThrownBy(() -> service.get(first))
                .isInstanceOfSatisfying(ServerException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(
                                ServerErrorCode
                                        .SCENARIO_RPC_RESOURCE_NOT_FOUND
                        )
                );
        assertThat(service.get(second).status()).isEqualTo("created");
    }

    private ScenarioRpcService service(
            ScenarioRpcTaskBatchExchange exchange,
            int maxScenarios
    ) throws Exception {
        ScenarioRpcProperties properties = new ScenarioRpcProperties(
                temporaryDirectory.resolve("data")
                        .resolve("rpc-task")
                        .toString(),
                1024,
                10,
                maxScenarios,
                100,
                300
        );
        Clock clock = Clock.fixed(
                Instant.ofEpochMilli(1_786_680_000_123L),
                ZoneOffset.UTC
        );
        return new ScenarioRpcService(
                com.xa.mass.scenariorpc.ScenarioRpcEngine.create(),
                ScenarioRpcFileStore.open(properties),
                exchange,
                new ScenarioRpcInstanceRegistry(maxScenarios),
                properties,
                clock
        );
    }

    private static ScenarioRpcTaskBatchExchange successfulExchange() {
        ScenarioRpcTaskBatchExchange exchange = mock(
                ScenarioRpcTaskBatchExchange.class
        );
        doNothing().when(exchange).append(any(), anyList());
        when(exchange.loadResults(any(), anyList())).thenAnswer(invocation ->
                successfulResults(invocation.getArgument(1))
        );
        return exchange;
    }

    private static Map<String, Map<String, Object>> successfulResults(
            List<String> pending
    ) {
        Map<String, Map<String, Object>> results = new LinkedHashMap<>();
        int index = 0;
        for (String messageId : pending) {
            String field = messageId.contains("string.sha1")
                    ? "sha1"
                    : "md5";
            String value = index++ == 0 ? "hello" : "world";
            results.put(
                    messageId,
                    Map.of("valid", true, field, "hash-" + value)
            );
        }
        return results;
    }
}
