package com.xa.mass.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.server.api.v1.ResourceCommandController;
import com.xa.mass.server.api.v1.TaskControlController;
import com.xa.mass.server.api.v1.TaskDataController;
import com.xa.mass.server.api.v1.WorkerGroupTaskController;
import com.xa.mass.server.api.v1.runtimeview.RuntimeViewController;
import com.xa.mass.server.api.v1.workerdelivery.AdapterBatchDeliveryController;
import com.xa.mass.server.api.v1.workerdelivery.AdapterDirectCallController;
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
import com.xa.mass.kernel.worker.MappedWorkerPropertyIndexRuntime;
import com.xa.mass.kernel.worker.WorkerPropertyIndexRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime;
import com.xa.mass.kernel.worker.redis.RedisWorkerResourceCatalog;
import com.xa.mass.kernel.worker.redis.RedisWorkerRuntime;
import com.xa.mass.server.kernelbinding.KernelOwnerAssemblyConfiguration;
import com.xa.mass.server.kernelbinding.WorkerPropertyIndexProperties;
import com.xa.mass.server.directcall.DirectCallService;
import com.xa.mass.server.runtimeview.RuntimeViewService;
import com.xa.mass.server.taskdata.WorkerGroupTaskCallService;
import com.xa.mass.server.taskdata.WorkerGroupTaskCatalog;
import com.xa.mass.server.workerdelivery.WorkerDeliveryOwnerAssemblyConfiguration;
import com.xa.mass.server.workerdelivery.application.WorkerDeliveryService;
import com.xa.mass.scenarioworkers.ScenarioWorkers;
import com.xa.mass.server.workerassembly
        .ServerWorkerAssemblyLifecycleHost;
import com.xa.mass.server.workerdelivery.adapter
        .ServerWorkerDeliveryAdapterProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
                WorkerGroupTaskController.class
        )).isNotNull();
        assertThat(applicationContext.getBean(
                WorkerGroupTaskCallService.class
        )).isNotNull();
        assertThat(applicationContext.getBean(
                WorkerGroupTaskCatalog.class
        )).isNotNull();
        assertThat(applicationContext.getBean(RuntimeViewController.class))
                .isNotNull();
        assertThat(applicationContext.getBean(RuntimeViewService.class))
                .isNotNull();
        assertThat(applicationContext.getBean(
                WorkerPointDeliveryController.class
        )).isNotNull();
        assertThat(applicationContext.getBean(
                AdapterBatchDeliveryController.class
        )).isNotNull();
        assertThat(applicationContext.getBean(
                AdapterDirectCallController.class
        )).isNotNull();
        assertThat(applicationContext.getBean(
                DirectCallService.class
        )).isNotNull();
        assertThat(applicationContext.getBeansOfType(
                TaskScoreBandCore.class
        )).isEmpty();
        assertThat(applicationContext.getBeansOfType(
                TaskItemScoreBandCore.class
        )).isEmpty();
        assertThat(applicationContext.getBean(WorkerScoreCore.class))
                .isInstanceOf(RedisWorkerScoreCore.class);
        assertThat(applicationContext.getBean(
                WorkerPropertyIndexRuntime.class
        )).isInstanceOf(MappedWorkerPropertyIndexRuntime.class);
        assertThat(applicationContext.getBean(
                WorkerPropertyIndexProperties.class
        ).registry()).isEmpty();
        assertThat(applicationContext.getBeansOfType(
                CandidateWorkerCache.class
        )).isEmpty();
        assertThat(applicationContext.getBeansOfType(
                CandidateWarmupSchedule.class
        )).isEmpty();
        assertThat(applicationContext.getBean(
                ServerWorkerDeliveryAdapterProperties.class
        ).instanceConfigs()).isEmpty();
        assertThat(applicationContext.getBean(
                ServerWorkerAssemblyLifecycleHost.class
        )).isNotNull();
        assertThat(applicationContext.getBean(ScenarioWorkers.class))
                .isNotNull();

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

    @Test
    void rejectsRemovedWorkerResourceFields() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> groupResponse = client.send(
                HttpRequest.newBuilder(endpoint(
                                "/api/v1/worker-groups/legacy-group"
                        ))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(
                                "{\"eventCodes\":[\"event\"],"
                                        + "\"itemAllocation"
                                        + "Fields\":[]}",
                                StandardCharsets.UTF_8
                        ))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        HttpResponse<String> indexedGroupResponse = client.send(
                HttpRequest.newBuilder(endpoint(
                                "/api/v1/worker-groups/indexed-legacy-group"
                        ))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(
                                "{\"eventCodes\":[\"event\"],"
                                        + "\"indexedPropertyFields\":["
                                        + "\"worker.region\"]}",
                                StandardCharsets.UTF_8
                        ))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        HttpResponse<String> workerResponse = client.send(
                HttpRequest.newBuilder(endpoint(
                                "/api/v1/worker-groups/legacy-group/"
                                        + "workers:register"
                        ))
                        .header("Content-Type", "application/json")
                        .header(
                                "X-XA-Mass-Platform-Key",
                                "local-development"
                        )
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"clientWorkerKey\":\"legacy-worker\","
                                        + "\"attributes\":{}}",
                                StandardCharsets.UTF_8
                        ))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        HttpResponse<String> bindingResponse = client.send(
                HttpRequest.newBuilder(endpoint(
                                "/api/v1/worker-groups/legacy-group/workers/"
                                        + "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1"
                                        + ":bind"
                        ))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"clientWorkerKey\":\"legacy-worker\","
                                        + "\"transportType\":\"WEBSOCKET\","
                                        + "\"workerProperties\":{"
                                        + "\"clientWorkerKey\":"
                                        + "\"legacy-worker\"}}",
                                StandardCharsets.UTF_8
                        ))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        assertThat(groupResponse.statusCode()).isEqualTo(400);
        assertThat(groupResponse.body()).contains("\"code\":19001");
        assertThat(indexedGroupResponse.statusCode()).isEqualTo(400);
        assertThat(indexedGroupResponse.body()).contains("\"code\":19001");
        assertThat(workerResponse.statusCode()).isEqualTo(400);
        assertThat(workerResponse.body()).contains("\"code\":19001");
        assertThat(bindingResponse.statusCode()).isEqualTo(400);
        assertThat(bindingResponse.body()).contains("\"code\":19001");
    }

    private URI endpoint(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
