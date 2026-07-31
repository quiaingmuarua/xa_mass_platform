package com.xa.mass.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.server.api.v1.ResourceCommandController;
import com.xa.mass.server.api.v1.TaskControlController;
import com.xa.mass.server.api.v1.TaskDataController;
import com.xa.mass.server.api.v1.workerdelivery.AdapterBatchDeliveryController;
import com.xa.mass.server.api.v1.workerdelivery.WorkerPointDeliveryController;
import com.xa.mass.kernel.assignment.CandidateWarmupSchedule;
import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.delivery.WorkerResultRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.redis.RedisWorkerScoreCore;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.worker.WorkerDynamicAttributeRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime;
import com.xa.mass.kernel.worker.redis.RedisWorkerResourceCatalog;
import com.xa.mass.kernel.worker.redis.RedisWorkerRuntime;
import com.xa.mass.server.kernelbinding.KernelOwnerAssemblyConfiguration;
import com.xa.mass.server.workerdelivery.WorkerDeliveryOwnerAssemblyConfiguration;
import com.xa.mass.server.workerdelivery.application.WorkerDeliveryService;
import com.xa.mass.server.workerassembly
        .ServerWorkerBundleManager;
import com.xa.mass.server.workerassembly
        .ServerWorkerAssemblyLifecycleHost;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "xa.mass.kernel.base-url=http://127.0.0.1:1",
                "xa.mass.kernel.connect-timeout=10ms",
                "xa.mass.kernel.read-timeout=10ms"
        }
)
class ServerApplicationContextTest {

    @Autowired
    private ApplicationContext applicationContext;

    @LocalServerPort
    private int port;

    @Test
    void assemblesTheRuntimeApiWithoutStartingThePythonKernel() throws Exception {
        assertThat(applicationContext.getBean(TaskRuntime.class))
                .isNotNull();
        assertThat(applicationContext.getBean(TaskResourceCatalog.class))
                .isNotNull();
        assertThat(applicationContext.getBean(WorkerRuntime.class))
                .isInstanceOf(RedisWorkerRuntime.class);
        assertThat(applicationContext.getBean(WorkerResourceCatalog.class))
                .isInstanceOf(RedisWorkerResourceCatalog.class);
        assertThat(applicationContext.getBean(WorkerCommandRuntime.class))
                .isNotNull();
        assertThat(applicationContext.getBean(WorkerResultRuntime.class))
                .isNotNull();
        assertThat(applicationContext.getBean(
                KernelOwnerAssemblyConfiguration.class
        )).isNotNull();
        assertThat(applicationContext.getBean(
                WorkerDeliveryOwnerAssemblyConfiguration.class
        )).isNotNull();
        assertThat(applicationContext.getBean(WorkerDeliveryService.class))
                .isNotNull();
        assertThat(applicationContext.getBean(ResourceCommandController.class))
                .isNotNull();
        assertThat(applicationContext.getBean(TaskControlController.class))
                .isNotNull();
        assertThat(applicationContext.getBean(TaskDataController.class))
                .isNotNull();
        assertThat(applicationContext.getBean(
                WorkerPointDeliveryController.class
        )).isNotNull();
        assertThat(applicationContext.getBean(
                AdapterBatchDeliveryController.class
        )).isNotNull();
        assertThat(applicationContext.getBeansOfType(
                TaskScoreBandCore.class
        )).isEmpty();
        assertThat(applicationContext.getBeansOfType(
                TaskItemScoreBandCore.class
        )).isEmpty();
        assertThat(applicationContext.getBean(WorkerScoreCore.class))
                .isInstanceOf(RedisWorkerScoreCore.class);
        assertThat(applicationContext.getBeansOfType(
                WorkerDynamicAttributeRuntime.class
        )).isEmpty();
        assertThat(applicationContext.getBeansOfType(
                CandidateWorkerCache.class
        )).isEmpty();
        assertThat(applicationContext.getBeansOfType(
                CandidateWarmupSchedule.class
        )).isEmpty();
        assertThat(applicationContext.getBean(
                WorkerDeliveryAdapterManager.class
        ).adapters()).isEmpty();
        assertThat(applicationContext.getBean(
                ServerWorkerAssemblyLifecycleHost.class
        )).isNotNull();
        assertThat(applicationContext.getBean(
                ServerWorkerBundleManager.class
        ).bundleIds()).isEmpty();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> liveness = client.send(
                HttpRequest.newBuilder(endpoint("/actuator/health/liveness"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        HttpResponse<String> readiness = client.send(
                HttpRequest.newBuilder(endpoint("/actuator/health/readiness"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(liveness.statusCode()).isEqualTo(200);
        assertThat(liveness.body()).contains("\"status\":\"UP\"");
        assertThat(readiness.statusCode()).isEqualTo(503);
        assertThat(readiness.body()).contains("\"status\":\"DOWN\"");
    }

    private URI endpoint(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
