package com.xa.mass.server.kernelclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.xa.mass.server.api.v1.model.RuntimeCommandStatus;
import com.xa.mass.server.api.v1.model.TaskCreateRequest;
import com.xa.mass.server.api.v1.model.TaskType;
import com.xa.mass.server.api.v1.model.WorkerGroupUpsertRequest;
import com.xa.mass.server.api.v1.model.WorkerUpsertRequest;
import java.io.IOException;
import java.net.ConnectException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpKernelCommandClientTest {

    private MockRestServiceServer server;
    private HttpKernelCommandClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://kernel");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpKernelCommandClient(builder.build());
    }

    @Test
    void forwardsResourceCommandsToThePythonRuntimeApi() {
        server.expect(requestTo("http://kernel/worker-groups/phone-tools"))
                .andExpect(method(PUT))
                .andExpect(content().json("""
                        {
                          "attributes": {},
                          "eventCodes": ["telecom.phone.inspect"],
                          "itemAllocationFields": ["workerId"]
                        }
                        """))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"ok\"}"));
        server.expect(requestTo(
                        "http://kernel/worker-groups/phone-tools/workers/worker-1"
                ))
                .andExpect(method(PUT))
                .andExpect(content().json("""
                        {
                          "endpointManagerId": "endpoint-manager-1",
                          "attributes": {"runtime": "java"},
                          "dynamicAttributeNames": []
                        }
                        """))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"noop\"}"));

        KernelResponse<?> groupResult = client.upsertWorkerGroup(
                "phone-tools",
                new WorkerGroupUpsertRequest(
                        Map.of(),
                        List.of("telecom.phone.inspect"),
                        List.of("workerId")
                )
        );
        KernelResponse<?> workerResult = client.upsertWorker(
                "phone-tools",
                "worker-1",
                new WorkerUpsertRequest(
                        "endpoint-manager-1",
                        Map.of("runtime", "java"),
                        List.of()
                )
        );

        assertThat(groupResult.body())
                .extracting("status")
                .isEqualTo(RuntimeCommandStatus.OK);
        assertThat(workerResult.body())
                .extracting("status")
                .isEqualTo(RuntimeCommandStatus.NOOP);
        server.verify();
    }

    @Test
    void forwardsTaskLifecycleCommandsWithoutMutationRetries() {
        server.expect(requestTo("http://kernel/tasks"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {
                          "taskId": "task-1",
                          "workerGroupId": "phone-tools",
                          "taskType": "TASK_DRIVEN",
                          "allocationRule": {},
                          "config": {
                            "priority": "0",
                            "maximumCandidateWorkers": "1",
                            "maxRetryTimes": "3"
                          },
                          "emptyCloseAtMillis": 0
                        }
                        """))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"created\"}"));
        server.expect(requestTo("http://kernel/tasks/task-1/approve"))
                .andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"approved\"}"));
        server.expect(requestTo("http://kernel/tasks/task-1/close"))
                .andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"closed\"}"));

        KernelResponse<?> created = client.createTask(taskRequest("task-1"));
        KernelResponse<?> approved = client.approveTask("task-1");
        KernelResponse<?> closed = client.closeTask("task-1");

        assertThat(created.statusCode().value()).isEqualTo(201);
        assertThat(created.body()).extracting("status")
                .isEqualTo(RuntimeCommandStatus.CREATED);
        assertThat(approved.body()).extracting("status")
                .isEqualTo(RuntimeCommandStatus.APPROVED);
        assertThat(closed.body()).extracting("status")
                .isEqualTo(RuntimeCommandStatus.CLOSED);
        server.verify();
    }

    @Test
    void exposesOwnerConflictWithoutReclassifyingIt() {
        server.expect(requestTo("http://kernel/tasks"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "status": "conflict",
                                  "reason": "task already exists"
                                }
                                """));

        KernelResponse<?> response = client.createTask(taskRequest("task-1"));

        assertThat(response.statusCode().value()).isEqualTo(409);
        assertThat(response.body()).extracting("status")
                .isEqualTo(RuntimeCommandStatus.CONFLICT);
        server.verify();
    }

    @Test
    void rejectsMalformedKernelResponses() {
        server.expect(requestTo("http://kernel/tasks"))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"unexpected\":true}"));

        assertThatThrownBy(() -> client.createTask(taskRequest("task-1")))
                .isInstanceOf(KernelClientException.class)
                .satisfies(error -> assertThat(
                        ((KernelClientException) error).responseStatus()
                ).isEqualTo(HttpStatus.BAD_GATEWAY));
        server.verify();
    }

    @Test
    void mapsPythonValidationDetailWithoutLeakingItsBody() {
        server.expect(requestTo("http://kernel/tasks"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"task config is invalid\"}"));

        assertThatThrownBy(() -> client.createTask(taskRequest("task-1")))
                .isInstanceOf(KernelClientException.class)
                .satisfies(error -> {
                    KernelClientException kernelError =
                            (KernelClientException) error;
                    assertThat(kernelError.responseStatus().value())
                            .isEqualTo(422);
                    assertThat(kernelError.errorCode()).isEqualTo("KERNEL_REJECTED");
                });
        server.verify();
    }

    @Test
    void classifiesConnectionFailureAsUnavailable() {
        HttpKernelCommandClient failingClient = clientThatThrows(
                new ConnectException("connection refused")
        );

        assertThatThrownBy(() -> failingClient.createTask(taskRequest("task-1")))
                .isInstanceOf(KernelClientException.class)
                .satisfies(error -> {
                    KernelClientException kernelError =
                            (KernelClientException) error;
                    assertThat(kernelError.responseStatus())
                            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(kernelError.errorCode())
                            .isEqualTo("KERNEL_UNAVAILABLE");
                });
    }

    @Test
    void classifiesSocketTimeoutAsGatewayTimeout() {
        HttpKernelCommandClient failingClient = clientThatThrows(
                new java.net.SocketTimeoutException("timed out")
        );

        assertThatThrownBy(() -> failingClient.createTask(taskRequest("task-1")))
                .isInstanceOf(KernelClientException.class)
                .satisfies(error -> {
                    KernelClientException kernelError =
                            (KernelClientException) error;
                    assertThat(kernelError.responseStatus())
                            .isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
                    assertThat(kernelError.errorCode()).isEqualTo("KERNEL_TIMEOUT");
                });
    }

    private static TaskCreateRequest taskRequest(String taskId) {
        return new TaskCreateRequest(
                taskId,
                "phone-tools",
                TaskType.TASK_DRIVEN,
                Map.of(),
                Map.of(
                        "priority", "0",
                        "maximumCandidateWorkers", "1",
                        "maxRetryTimes", "3"
                ),
                0L
        );
    }

    private static HttpKernelCommandClient clientThatThrows(IOException failure) {
        RestClient restClient = RestClient.builder()
                .baseUrl("http://kernel")
                .requestFactory((uri, method) ->
                        new MockClientHttpRequest(method, uri) {
                            @Override
                            protected ClientHttpResponse executeInternal()
                                    throws IOException {
                                throw failure;
                            }
                        })
                .build();
        return new HttpKernelCommandClient(restClient);
    }
}
