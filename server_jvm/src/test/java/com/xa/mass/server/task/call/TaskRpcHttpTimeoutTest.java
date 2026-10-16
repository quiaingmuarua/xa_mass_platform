package com.xa.mass.server.task.call;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemResult;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import tools.jackson.databind.json.JsonMapper;

/** Real servlet timeout, controlled take; Redis atomicity is owned by the Redis lane. */
@Timeout(30)
class TaskRpcHttpTimeoutTest {
    @ParameterizedTest(name = "virtual={0}")
    @ValueSource(booleans = {false, true})
    void servletTimeoutWaitsForItsAdmittedTakeAndReturnsTheConsumedResult(boolean virtual) throws Exception {
        try (var context = new SpringApplicationBuilder(HttpFixture.class).run(
                "--server.port=0", "--spring.threads.virtual.enabled=" + virtual,
                "--spring.config.location=optional:classpath:/rpc-timeout-fixture.properties");
             var client = HttpClient.newHttpClient()) {
            var gate = context.getBean(TakeGate.class);
            var registry = context.getBean(TaskRpcWaitRegistry.class);
            var runtime = context.getBean(TaskRuntime.class);
            String base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
            var response = client.sendAsync(HttpRequest.newBuilder(URI.create(base + "/test/rpc-timeout"))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            try {
                assertThat(gate.takeEntered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(gate.timeoutEntered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(response).isNotDone();
                assertThat(client.send(HttpRequest.newBuilder(URI.create(base + "/test/ping"))
                        .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode())
                        .isEqualTo(200);
            } finally {
                gate.finishTake.countDown();
            }
            var completed = response.get(5, TimeUnit.SECONDS);
            assertThat(completed.statusCode()).isEqualTo(200);
            var result = JsonMapper.builder().build().readTree(completed.body()).get("message");
            assertThat(result.get("status").asText()).isEqualTo("succeeded");
            assertThat(result.get("opaqueResultPayload").asText()).isEqualTo("taken result");
            assertThat(registry.waiterCount()).isZero();
            assertThat(registry.pendingObservationCount()).isZero();
            verify(runtime).takeTaskItemResults("task", List.of("message"));
            verifyNoMoreInteractions(runtime);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({TaskRpcWaitRegistry.class, TaskRpcResultProbe.class, TimeoutController.class})
    static class HttpFixture {
        @Bean TakeGate gate() { return new TakeGate(); }
        @Bean TaskRpcProperties properties() {
            return new TaskRpcProperties(Duration.ofDays(7), 50, 1000, 10, 100, 100, 50, 100, 250, Map.of());
        }
        @Bean TaskRuntime runtime(TakeGate gate) {
            var runtime = mock(TaskRuntime.class);
            when(runtime.takeTaskItemResults("task", List.of("message"))).thenAnswer(ignored -> {
                gate.takeEntered.countDown();
                if (!gate.finishTake.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test take gate expired");
                return Map.of("message", TaskItemResult.succeeded("taken result"));
            });
            return runtime;
        }
    }

    static class TakeGate {
        final CountDownLatch takeEntered = new CountDownLatch(1);
        final CountDownLatch timeoutEntered = new CountDownLatch(1);
        final CountDownLatch finishTake = new CountDownLatch(1);
    }

    @RestController
    @TestComponent
    static class TimeoutController {
        private final TaskRpcWaitRegistry registry;
        private final TakeGate gate;
        TimeoutController(TaskRpcWaitRegistry registry, TakeGate gate) { this.registry = registry; this.gate = gate; }
        @GetMapping("/test/rpc-timeout")
        DeferredResult<Map<String, TaskItemResultResponse>> call() {
            var result = new DeferredResult<Map<String, TaskItemResultResponse>>(50L);
            if (!registry.tryRegister("task", List.of("message"), Map.of(), result)) throw new IllegalStateException("fixture rejected");
            Runnable timeout = (Runnable) ReflectionTestUtils.getField(result, "timeoutCallback");
            result.onTimeout(() -> { gate.timeoutEntered.countDown(); timeout.run(); });
            return result;
        }
        @GetMapping("/test/ping") String ping() { return "ok"; }
    }
}
