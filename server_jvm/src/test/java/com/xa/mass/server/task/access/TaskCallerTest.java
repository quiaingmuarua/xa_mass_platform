package com.xa.mass.server.task.access;

import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.project.ProjectDefinition;
import com.xa.mass.server.task.directory.TaskAttribution;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TaskCallerTest {
    private String encode(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private TaskRuntime.TaskDescriptor task(String project, String owner, String input) {
        return new TaskRuntime.TaskDescriptor("t", "g", TaskRuntime.TaskIdleDisposition.CLOSE_WHEN_IDLE,
                Map.of("priority", "0", "maxRetryTimes", "3"), List.of(), null,
                new TaskAttribution(project, 123, owner).stamp(Map.of(TaskCaller.INPUT_OWNER, input)));
    }
    @Test void parsesOnlyUnambiguousDevelopmentUsersAndNeverAnInternalIdentity() {
        var caller = TaskCaller.fromHeaders(new String[]{encode("  outside1  ")}, new String[]{"operator"});
        assertThat(caller.userId()).isEqualTo("outside1");
        assertThat(caller.selectUser(null)).isEqualTo("outside1");
        for (String[] users : new String[][]{null, {}, {"***"}, {"_w"}, {encode("1001"), encode("1002")}})
            assertThatThrownBy(() -> TaskCaller.fromHeaders(users, new String[]{"admin"}))
                    .isInstanceOfSatisfying(ServerException.class, error -> assertThat(error.errorCode()).isEqualTo(ServerErrorCode.INVALID_TASK_CALLER));
        for (String[] roles : new String[][]{null, {}, {"service"}, {"ADMIN"}, {"admin", "operator"}})
            assertThatThrownBy(() -> TaskCaller.fromHeaders(new String[]{encode("1001")}, roles)).isInstanceOf(ServerException.class);
    }
    @Test void ownershipAndInputAuthorityAreIndependentAndServiceAccessIsProjectBounded() {
        var own = new TaskCaller("1001", "operator");
        var descriptor = task("messages", "1001", "messages");
        own.requireTask(descriptor);
        assertThatThrownBy(() -> own.requireInput(descriptor, TaskCaller.RAW_INPUT))
                .isInstanceOfSatisfying(ServerException.class, error -> assertThat(error.errorCode()).isEqualTo(ServerErrorCode.TASK_OPERATION_NOT_SUPPORTED));
        assertThatThrownBy(() -> new TaskCaller("1002", "operator").requireTask(descriptor))
                .isInstanceOfSatisfying(ServerException.class, error -> assertThat(error.errorCode()).isEqualTo(ServerErrorCode.TASK_ACCESS_DENIED));
        assertThatThrownBy(() -> own.selectUser("1002")).isInstanceOf(ServerException.class);
        new TaskCaller("1000", "admin").requireTask(descriptor);
        var service = TaskCaller.forProjectService(new ProjectDefinition("sms", List.of("g")));
        service.requireInput(task("sms", null, "sms"), "sms");
        assertThatThrownBy(service::requireAdmin).isInstanceOf(ServerException.class);
        assertThatThrownBy(() -> service.requireTask(descriptor)).isInstanceOf(ServerException.class);
        assertThatThrownBy(() -> own.requireTask(task("sms", null, "sms"))).isInstanceOf(ServerException.class);
    }
}
