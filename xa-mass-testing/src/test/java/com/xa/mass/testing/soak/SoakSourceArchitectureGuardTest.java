package com.xa.mass.testing.soak;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class SoakSourceArchitectureGuardTest {

    @Test
    void soakRunnerDoesNotUseProjectionFirstProofSurface() throws Exception {
        Path source = Path.of(
                "src/main/java/com/xa/mass/testing/soak/SdkPollingSchedulingSoakRunner.java"
        );
        if (!Files.isRegularFile(source)) {
            source = Path.of("xa-mass-testing").resolve(source);
        }
        String content = Files.readString(source);
        List<String> forbiddenTokens = List.of(
                "TaskDetailStore",
                "ProjectionTestViews",
                "CompatibilityMessageView",
                "CompatibilityAttemptView",
                "TaskMessageProjection",
                "TaskMessageStats",
                "TaskMessageAttemptStats",
                "getTaskMessage",
                "waitForSingleMessage",
                "taskDetailStore()"
        );
        for (String token : forbiddenTokens) {
            assertFalse(content.contains(token), "soak runner must not use projection-first token: " + token);
        }
    }
}
