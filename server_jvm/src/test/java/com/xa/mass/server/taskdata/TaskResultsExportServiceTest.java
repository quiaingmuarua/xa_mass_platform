package com.xa.mass.server.taskdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreState;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemSuccessResultPage;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class TaskResultsExportServiceTest {

    private TaskResourceCatalog taskCatalog;
    private TaskScoreBandCore taskScores;
    private TaskRuntime taskRuntime;
    private TaskResultsExportService service;

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void setUp() {
        taskCatalog = mock(TaskResourceCatalog.class);
        taskScores = mock(TaskScoreBandCore.class);
        taskRuntime = mock(TaskRuntime.class);
        service = new TaskResultsExportService(
                taskCatalog,
                taskScores,
                taskRuntime,
                JsonMapper.builder().build(),
                temporaryDirectory
        );
        when(taskCatalog.loadTaskAllocationDescriptors(anyList()))
                .thenReturn(Map.of("task-1", finiteTask("task-1")));
        doReturn(Map.of(
                        "task-1",
                        score("task-1", TaskScoreBand.TERMINAL)
                ))
                .when(taskScores)
                .getScoreStates(List.of("task-1"));
    }

    @Test
    void terminalTaskExportsOpaqueResultsOnceAndDeletesAfterTransfer()
            throws Exception {
        Map<String, String> firstPage = new LinkedHashMap<>();
        firstPage.put("message-1", "{\"md5\":\"abc\"}");
        firstPage.put("message-2", "plain-text");
        when(taskRuntime.scanTaskItemSuccessResults("task-1", "0", 1000))
                .thenReturn(new TaskItemSuccessResultPage(
                        "7",
                        firstPage
                ));
        when(taskRuntime.scanTaskItemSuccessResults("task-1", "7", 1000))
                .thenReturn(new TaskItemSuccessResultPage(
                        "0",
                        Map.of("message-1", "newer-value")
                ));

        var export = service.export("task-1", 30_000L);

        assertThat(export.ready()).isTrue();
        assertThat(Files.readAllLines(export.file())).containsExactly(
                "{\"messageId\":\"message-1\","
                        + "\"opaqueResultPayload\":"
                        + "\"{\\\"md5\\\":\\\"abc\\\"}\"}",
                "{\"messageId\":\"message-2\","
                        + "\"opaqueResultPayload\":\"plain-text\"}"
        );

        var output = new ByteArrayOutputStream();
        service.transferAndDelete(export.file(), output);

        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("message-1")
                .contains("message-2")
                .doesNotContain("newer-value");
        assertThat(export.file()).doesNotExist();
    }

    @Test
    void nonTerminalTaskReturnsNotReadyWithoutScanningResults() {
        when(taskScores.getScoreStates(List.of("task-1")))
                .thenReturn(Map.of(
                        "task-1",
                        score("task-1", TaskScoreBand.RUNNING_VISIBLE)
                ));

        var export = service.export("task-1", 1L);

        assertThat(export.ready()).isFalse();
        assertThat(export.file()).isNull();
        verifyNoInteractions(taskRuntime);
    }

    @Test
    void emptyTerminalResultHashProducesAnEmptyDownload() throws Exception {
        when(taskRuntime.scanTaskItemSuccessResults("task-1", "0", 1000))
                .thenReturn(new TaskItemSuccessResultPage("0", Map.of()));

        var export = service.export("task-1", null);
        var output = new ByteArrayOutputStream();
        service.transferAndDelete(export.file(), output);

        assertThat(output.size()).isZero();
        assertThat(export.file()).doesNotExist();
    }

    @Test
    void missingAndManagedTasksAreRejectedBeforeScoreObservation() {
        when(taskCatalog.loadTaskAllocationDescriptors(List.of("missing")))
                .thenReturn(Map.of());
        when(taskCatalog.loadTaskAllocationDescriptors(List.of("managed")))
                .thenReturn(Map.of("managed", managedTask("managed")));

        assertThatThrownBy(() -> service.export("missing", null))
                .isInstanceOfSatisfying(ServerException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(
                                ServerErrorCode.TASK_NOT_FOUND
                        ));
        assertThatThrownBy(() -> service.export("managed", null))
                .isInstanceOfSatisfying(ServerException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(
                                ServerErrorCode.TASK_OPERATION_NOT_SUPPORTED
                        ));
        verifyNoInteractions(taskRuntime);
    }

    @Test
    void invalidWaitAndResultOwnerFailureUseTaskDataErrorsAndCleanFiles()
            throws Exception {
        assertThatThrownBy(() -> service.export("task-1", 0L))
                .isInstanceOfSatisfying(ServerException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(
                                ServerErrorCode.INVALID_TASK_DATA_REQUEST
                        ));
        when(taskRuntime.scanTaskItemSuccessResults("task-1", "0", 1000))
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertThatThrownBy(() -> service.export("task-1", null))
                .isInstanceOfSatisfying(ServerException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(
                                ServerErrorCode.TASK_DATA_UNAVAILABLE
                        ));
        try (var files = Files.list(temporaryDirectory)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void scoreAndTemporaryFileFailuresAreUnavailable() throws Exception {
        when(taskScores.getScoreStates(List.of("task-1")))
                .thenThrow(new IllegalStateException("score unavailable"));
        assertThatThrownBy(() -> service.export("task-1", null))
                .isInstanceOfSatisfying(ServerException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(
                                ServerErrorCode.TASK_DATA_UNAVAILABLE
                        ));

        Path notDirectory = temporaryDirectory.resolve("not-a-directory");
        Files.writeString(notDirectory, "occupied");
        var invalidFileService = new TaskResultsExportService(
                taskCatalog,
                taskScores,
                taskRuntime,
                JsonMapper.builder().build(),
                notDirectory
        );
        doReturn(Map.of(
                        "task-1",
                        score("task-1", TaskScoreBand.TERMINAL)
                ))
                .when(taskScores)
                .getScoreStates(List.of("task-1"));
        assertThatThrownBy(() -> invalidFileService.export("task-1", null))
                .isInstanceOfSatisfying(ServerException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(
                                ServerErrorCode.TASK_DATA_UNAVAILABLE
                        ));
    }

    @Test
    void failedResponseTransferStillDeletesTheTemporaryFile()
            throws Exception {
        when(taskRuntime.scanTaskItemSuccessResults("task-1", "0", 1000))
                .thenReturn(new TaskItemSuccessResultPage(
                        "0",
                        Map.of("message-1", "result")
                ));
        var export = service.export("task-1", null);
        OutputStream failingOutput = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("client disconnected");
            }
        };

        assertThatThrownBy(() -> service.transferAndDelete(
                export.file(),
                failingOutput
        )).isInstanceOf(IOException.class);
        assertThat(export.file()).doesNotExist();
    }

    private static TaskDescriptor finiteTask(String taskId) {
        return descriptor(
                taskId,
                WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE,
                TaskIdleDisposition.CLOSE_WHEN_IDLE,
                Map.of()
        );
    }

    private static TaskDescriptor managedTask(String taskId) {
        return descriptor(
                taskId,
                WorkerAllocationMechanism.DIRECT_ITEM_RULE,
                TaskIdleDisposition.PARK_WHEN_IDLE,
                null
        );
    }

    private static TaskDescriptor descriptor(
            String taskId,
            WorkerAllocationMechanism mechanism,
            TaskIdleDisposition disposition,
            Map<String, Object> allocationRule
    ) {
        return new TaskDescriptor(
                taskId,
                "group-1",
                mechanism,
                disposition,
                allocationRule,
                Map.of(
                        "priority", "50",
                        "maximumCandidateWorkers", "10",
                        "maxRetryTimes", "3"
                )
        );
    }

    private static TaskScoreState score(
            String taskId,
            TaskScoreBand band
    ) {
        return new TaskScoreState(
                taskId,
                band == TaskScoreBand.TERMINAL ? -1 : 1,
                band,
                band == TaskScoreBand.TERMINAL ? null : 0L,
                band == TaskScoreBand.TERMINAL ? null : 1
        );
    }
}
