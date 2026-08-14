package com.xa.mass.server.scenariorpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.scenariorpc.ScenarioRpcResult;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.net.URI;
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
    void uploadsCreateOnlyInputAndPublishesJsonlAtomically()
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

        store.publishOutput("string.md5-1.jsonl", List.of(
                new ScenarioRpcResult(
                        "scenario-string-utils-workers",
                        "rpc-1-string.md5-00000001",
                        "string.md5",
                        Map.of("value", "hello"),
                        Map.of("valid", true, "md5", "hash")
                )
        ));
        String output = new String(
                store.readOutput("string.md5-1.jsonl"),
                StandardCharsets.UTF_8
        );
        assertThat(output)
                .contains("\"workerGroupId\"")
                .contains("\"messageId\"")
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
                URI.create("http://127.0.0.1:18082"),
                30_000,
                35_000,
                maxBytes,
                maxLines
        ));
    }

    private Path root() {
        return temporaryDirectory.resolve("data").resolve("rpc-task");
    }

    private Path outputDirectory() {
        return root().resolve("output");
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
