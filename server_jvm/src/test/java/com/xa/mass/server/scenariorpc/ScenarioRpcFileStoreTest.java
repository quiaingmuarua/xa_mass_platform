package com.xa.mass.server.scenariorpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.scenariorpc.ScenarioRpcResult;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioRpcFileStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void uploadsCreateOnlyInputAndPublishesIncrementalJsonl()
            throws Exception {
        ScenarioRpcFileStore store = open(1024, 10);
        byte[] content = "hello\nworld\n".getBytes(StandardCharsets.UTF_8);

        ScenarioRpcFileStore.StoredInput stored = store.upload(
                "strings.txt",
                content
        );
        assertThat(stored).isEqualTo(
                new ScenarioRpcFileStore.StoredInput(
                        "strings.txt",
                        content.length,
                        2
                )
        );
        assertThat(store.readInput("strings.txt"))
                .containsExactly("hello", "world");
        assertThatThrownBy(() -> store.upload("strings.txt", content))
                .isInstanceOfSatisfying(ServerException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(
                                ServerErrorCode.SCENARIO_RPC_CONFLICT
                        )
                );

        try (ScenarioRpcFileStore.OutputSession output =
                     store.output("scenario-1")) {
            output.accept(List.of(result("message-1", "hello")));
            assertThat(readTemporaryOutput()).contains("hash-hello");
            output.accept(List.of(result("message-2", "world")));
            assertThat(output.publish(false)).isEqualTo(
                    "scenario-1.jsonl"
            );
        }

        String published = new String(
                store.readOutput("scenario-1.jsonl"),
                StandardCharsets.UTF_8
        );
        assertThat(published)
                .contains("hash-hello")
                .contains("hash-world")
                .doesNotContain("taskId")
                .doesNotContain("workerId");
        try (var outputFiles = Files.list(outputDirectory())) {
            assertThat(outputFiles.noneMatch(path -> path.getFileName()
                    .toString()
                    .endsWith(".tmp")))
                    .isTrue();
        }
    }

    @Test
    void publishesPartialAndLeavesFailedTemporaryOutputUnexposed()
            throws Exception {
        ScenarioRpcFileStore store = open(1024, 10);
        try (ScenarioRpcFileStore.OutputSession output =
                     store.output("scenario-2")) {
            output.accept(List.of(result("message-1", "hello")));
            assertThat(output.publish(true)).isEqualTo(
                    "scenario-2.partial.jsonl"
            );
        }
        assertThat(new String(
                store.readOutput("scenario-2.partial.jsonl"),
                StandardCharsets.UTF_8
        )).contains("hash-hello");

        try (ScenarioRpcFileStore.OutputSession failed =
                     store.output("scenario-3")) {
            failed.accept(List.of(result("message-2", "world")));
        }
        assertThatThrownBy(() -> store.readOutput("scenario-3.jsonl"))
                .isInstanceOfSatisfying(ServerException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(
                                ServerErrorCode.SCENARIO_RPC_RESOURCE_NOT_FOUND
                        )
                );
        assertThat(readTemporaryOutput()).contains("hash-world");
    }

    @Test
    void rejectsUnsafeNamesInvalidUtf8AndConfiguredLimits()
            throws Exception {
        ScenarioRpcFileStore store = open(8, 1);

        assertInvalid(() -> store.upload("../escape.txt", new byte[0]));
        assertInvalid(() -> store.upload("input.jsonl", new byte[0]));
        assertInvalid(() -> store.upload("large.txt", new byte[9]));
        assertInvalid(() -> store.upload(
                "invalid.txt",
                new byte[]{(byte) 0xC3, (byte) 0x28}
        ));
        assertInvalid(() -> store.upload(
                "lines.txt",
                "a\nb".getBytes(StandardCharsets.UTF_8)
        ));
    }

    private ScenarioRpcFileStore open(int maxBytes, int maxLines)
            throws Exception {
        return ScenarioRpcFileStore.open(new ScenarioRpcProperties(
                root().toString(),
                maxBytes,
                maxLines,
                100,
                100,
                300
        ));
    }

    private Path root() {
        return temporaryDirectory.resolve("data").resolve("rpc-task");
    }

    private Path outputDirectory() {
        return root().resolve("output");
    }

    private String readTemporaryOutput() throws Exception {
        try (var files = Files.list(outputDirectory())) {
            Path temporary = files
                    .filter(path -> path.getFileName().toString()
                            .endsWith(".tmp"))
                    .findFirst()
                    .orElseThrow();
            return Files.readString(temporary, StandardCharsets.UTF_8);
        }
    }

    private static ScenarioRpcResult result(
            String messageId,
            String value
    ) {
        return new ScenarioRpcResult(
                "scenario-string-utils-workers",
                messageId,
                "string.md5",
                Map.of("value", value),
                Map.of("valid", true, "md5", "hash-" + value)
        );
    }

    private static void assertInvalid(ThrowingOperation operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ServerException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(
                                ServerErrorCode.SCENARIO_RPC_INVALID_REQUEST
                        )
                );
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }
}
