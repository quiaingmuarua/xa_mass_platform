package com.xa.mass.server.taskbatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskBatchFileStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void uploadsCreateOnlyAndAtomicallyPublishesOutput() throws Exception {
        TaskBatchFileStore files = files();
        var uploaded = files.upload(
                "input.txt",
                "one\n\ntwo\n".getBytes(StandardCharsets.UTF_8)
        );
        assertThat(uploaded.lineCount()).isEqualTo(3);
        assertThat(files.readInput("input.txt"))
                .containsExactly("one", "", "two");
        assertThatThrownBy(() -> files.upload("input.txt", new byte[0]))
                .isInstanceOfSatisfying(ServerException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(
                                ServerErrorCode.TASK_BATCH_CONFLICT
                        )
                );

        try (var output = files.output("task-batch-1")) {
            output.accept(List.of(new TaskBatchFileStore.OutputRow(
                    "group",
                    "message",
                    "event",
                    Map.of("value", "one"),
                    Map.of("valid", false)
            )));
            assertThat(output.publish(false)).isEqualTo(
                    "task-batch-1.jsonl"
            );
        }
        assertThat(new String(
                files.readOutput("task-batch-1.jsonl"),
                StandardCharsets.UTF_8
        )).contains("\"valid\":false");
    }

    @Test
    void failedOutputRemovesTemporaryEvidence() throws Exception {
        TaskBatchFileStore files = files();
        try (var output = files.output("task-batch-failed")) {
            output.accept(List.of(new TaskBatchFileStore.OutputRow(
                    "group",
                    "message",
                    "event",
                    Map.of(),
                    Map.of()
            )));
        }
        Path outputDirectory = temporaryDirectory.resolve("data")
                .resolve("rpc-task")
                .resolve("output");
        try (var children = Files.list(outputDirectory)) {
            assertThat(children.toList()).isEmpty();
        }
    }

    private TaskBatchFileStore files() throws Exception {
        return TaskBatchFileStore.open(new TaskBatchProperties(
                temporaryDirectory.resolve("data")
                        .resolve("rpc-task")
                        .toString(),
                1024,
                10,
                100,
                30_000,
                300_000
        ));
    }
}
