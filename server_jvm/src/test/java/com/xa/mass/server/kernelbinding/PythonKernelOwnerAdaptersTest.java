package com.xa.mass.server.kernelbinding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.xa.mass.kernel.task.TaskRuntime.TaskCreationStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskType;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDeclaration;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeStatus;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PythonKernelOwnerAdaptersTest {

    private MockRestServiceServer server;
    private PythonKernelHttpTransport transport;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://kernel.test");
        server = MockRestServiceServer.bindTo(builder).build();
        transport = new PythonKernelHttpTransport(builder.build());
    }

    @Test
    void ownerAdaptersMapKernelDtosWithoutExternalApiModels() {
        server.expect(requestTo(
                        "http://kernel.test/worker-groups/phone-tools"
                ))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("""
                        {
                          "attributes": {},
                          "eventCodes": ["telecom.phone.inspect"],
                          "itemAllocationFields": ["workerId"]
                        }
                        """))
                .andRespond(withSuccess(
                        "{\"status\":\"ok\"}",
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo(
                        "http://kernel.test/worker-groups/phone-tools/workers/worker-1"
                ))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess(
                        "{\"status\":\"ok\"}",
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo("http://kernel.test/tasks"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"status\":\"created\"}",
                        MediaType.APPLICATION_JSON
                ));

        var group = new HttpWorkerResourceCatalog(transport)
                .upsertWorkerGroup(new WorkerGroupDescriptor(
                        "phone-tools",
                        Map.of(),
                        Set.of("telecom.phone.inspect"),
                        Set.of("workerId")
                ));
        var worker = new HttpWorkerRuntime(transport).upsertWorker(
                new WorkerDeclaration(
                        "worker-1",
                        "phone-tools",
                        "system-polling",
                        Map.of(),
                        Set.of()
                )
        );
        var task = new HttpTaskRuntime(transport).createTask(
                new TaskDescriptor(
                        "task-1",
                        "phone-tools",
                        TaskType.TASK_DRIVEN,
                        Map.of(),
                        Map.of(
                                "priority", "0",
                                "maximumCandidateWorkers", "1",
                                "maxRetryTimes", "3"
                        ),
                        0L
                ),
                0
        );

        assertThat(group.status()).isEqualTo(WorkerRuntimeStatus.OK);
        assertThat(worker.status()).isEqualTo(WorkerRuntimeStatus.OK);
        assertThat(task.status()).isEqualTo(TaskCreationStatus.CREATED);
        server.verify();
    }

    @Test
    void lifecycleCommandsRemainApplicationOperations() {
        server.expect(requestTo(
                        "http://kernel.test/tasks/task-1/approve"
                ))
                .andRespond(withSuccess(
                        "{\"status\":\"approved\"}",
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo(
                        "http://kernel.test/tasks/task-1/close"
                ))
                .andRespond(withSuccess(
                        "{\"status\":\"closed\"}",
                        MediaType.APPLICATION_JSON
                ));

        var lifecycle = new HttpTaskLifecycleCommands(transport);
        assertThat(lifecycle.approveTask("task-1").status())
                .isEqualTo(
                        TaskLifecycleCommands.TaskApprovalStatus.APPROVED
                );
        assertThat(lifecycle.closeTask("task-1").status())
                .isEqualTo(TaskLifecycleCommands.TaskCloseStatus.CLOSED);
        server.verify();
    }

    @Test
    void dispatchWakeIsABoundedBestEffortControlCommand() {
        server.expect(requestTo(
                        "http://kernel.test/tasks:dispatch-wake"
                ))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"taskIds":["task-1","task-2"]}
                        """))
                .andRespond(withSuccess(
                        "{\"status\":\"accepted\","
                                + "\"acceptedTaskCount\":2}",
                        MediaType.APPLICATION_JSON
                ));

        new HttpTaskDispatchWakeCommands(transport)
                .wakeTaskDispatch(java.util.List.of(
                        "task-1",
                        "task-2"
                ));
        server.verify();
    }

    @Test
    void rejectedHttpStatusesMapToStableServerErrorCodes() {
        expectRejected(HttpStatus.NOT_FOUND);
        expectRejected(HttpStatus.CONFLICT);
        expectRejected(HttpStatus.UNPROCESSABLE_ENTITY);
        expectRejected(HttpStatus.SERVICE_UNAVAILABLE);
        expectRejected(HttpStatus.INTERNAL_SERVER_ERROR);

        assertRejected(
                HttpStatus.NOT_FOUND,
                ServerErrorCode.KERNEL_REJECTED_NOT_FOUND
        );
        assertRejected(
                HttpStatus.CONFLICT,
                ServerErrorCode.KERNEL_REJECTED_CONFLICT
        );
        assertRejected(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ServerErrorCode.KERNEL_REJECTED_INVALID
        );
        assertRejected(
                HttpStatus.SERVICE_UNAVAILABLE,
                ServerErrorCode.KERNEL_REJECTED_RETRYABLE
        );
        assertRejected(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ServerErrorCode.INVALID_KERNEL_RESPONSE
        );
        server.verify();
    }

    private void expectRejected(HttpStatus status) {
        String path = "/reject-" + status.value();
        server.expect(requestTo("http://kernel.test" + path))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(status)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"rejected\"}"));
    }

    private void assertRejected(
            HttpStatus status,
            ServerErrorCode expectedCode
    ) {
        String path = "/reject-" + status.value();
        assertThatThrownBy(() -> transport.post(path))
                .isInstanceOfSatisfying(
                        ServerException.class,
                        error -> {
                            assertThat(error.errorCode())
                                    .isEqualTo(expectedCode);
                            assertThat(error.operation())
                                    .isEqualTo("kernelBinding.exchange");
                        }
                );
    }
}
