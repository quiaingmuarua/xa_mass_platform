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
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import com.xa.mass.kernel.task.TaskCallItemSubmission.TaskCallSubmissionStatus;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.Map;
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
    void taskOwnerAdapterMapsKernelDtosWithoutExternalApiModels() {
        server.expect(requestTo("http://kernel.test/tasks"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"status\":\"created\"}",
                        MediaType.APPLICATION_JSON
                ));

        var task = new HttpTaskRuntime(transport).createTask(
                new TaskDescriptor(
                        "task-1",
                        "phone-tools",
                        WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE,
                        TaskIdleDisposition.CLOSE_WHEN_IDLE,
                        Map.of(),
                        Map.of(
                                "priority", "0",
                                "maximumCandidateWorkers", "1",
                                "maxRetryTimes", "3"
                        )
                ),
                0
        );

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
    void taskCallSubmissionUsesTheBoundedKernelApplicationCommand() {
        server.expect(requestTo(
                        "http://kernel.test/tasks/task-1:submit-call-items"
                ))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"items":[{
                          "messageId":"message-1",
                          "eventCode":"image.resize",
                          "createdAtMillis":1,
                          "payload":{},
                          "priority":5,
                          "expireAtMillis":null,
                          "allocationRule":{}
                        }]}
                        """))
                .andRespond(withSuccess(
                        "{\"status\":\"submitted\","
                                + "\"itemResults\":{\"message-1\":{"
                                + "\"status\":\"appended\"}}}",
                        MediaType.APPLICATION_JSON
                ));

        var result = new HttpTaskCallItemSubmission(transport).submit(
                "task-1",
                java.util.List.of(new TaskItem(
                        "message-1",
                        "image.resize",
                        1,
                        Map.of(),
                        5,
                        null,
                        Map.of()
                ))
        );

        assertThat(result.status()).isEqualTo(
                TaskCallSubmissionStatus.SUBMITTED
        );
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
