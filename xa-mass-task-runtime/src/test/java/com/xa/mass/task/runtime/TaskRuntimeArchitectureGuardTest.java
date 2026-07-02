package com.xa.mass.task.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskRuntimeArchitectureGuardTest {

    private static final List<Class<?>> PUBLIC_PORTS = List.of(
            TaskRuntimeReadPort.class,
            TaskRuntimeWorkPort.class,
            TaskRuntimeScorePort.class,
            TaskRuntimeConvergencePort.class
    );

    private static final List<String> DELETED_OLD_PORTS = List.of(
            "TaskRuntimeAppendPort",
            "TaskRuntimeSchedulerPort",
            "TaskRuntimeClaimPort",
            "TaskRuntimeResultPort",
            "TaskRuntimeRepairPort",
            "TaskRuntimeProgressPort",
            "TaskRuntimeDiscardPort",
            "TaskRuntimeResultWindowReadPort"
    );

    private static final List<String> DELETED_OLD_COMMAND_BUCKETS = List.of(
            "AppendBatchCommand",
            "SchedulerDiscoveryCommand",
            "ClaimReadyCommand",
            "ResultApplyCommand",
            "PollActiveLeaseRepairCommand",
            "ActiveWorkQuery",
            "ActiveTaskWorkQuery",
            "DiscardTaskRuntimeCommand",
            "DiscardTaskWorkCommand",
            "UpdateSchedulerEligibilityCommand"
    );

    private static final List<String> FORBIDDEN_SOURCE_SNIPPETS = List.of(
            "com.xa.mass.engine",
            "com.xa.mass.transport",
            "com.xa.mass.runtime.",
            "org.springframework",
            "io.lettuce",
            "redis.clients",
            "DiscardTaskWorkRuntime",
            "discardTaskWorkRuntime",
            "Thread",
            "ExecutorService",
            "ScheduledExecutor",
            "Runnable"
    );

    private static final List<String> FORBIDDEN_TYPE_PREFIXES = List.of(
            "com.xa.mass.engine.",
            "com.xa.mass.transport.",
            "com.xa.mass.runtime.",
            "org.springframework."
    );

    @Test
    void semanticModuleDoesNotImportPhysicalOrOwnerInternals() throws IOException {
        var violations = new ArrayList<String>();
        var mainJava = Path.of("src", "main", "java");
        try (var files = Files.walk(mainJava)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> collectForbiddenSnippetViolations(path, violations));
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void publicPortsDoNotExposeEngineTransportOrOldRuntimeTypes() {
        var violations = new ArrayList<String>();
        for (Class<?> port : PUBLIC_PORTS) {
            for (Method method : port.getMethods()) {
                collectForbiddenTypeViolation(port, method.getReturnType(), violations);
                for (Class<?> parameterType : method.getParameterTypes()) {
                    collectForbiddenTypeViolation(port, parameterType, violations);
                }
            }
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void oldCommandBucketPortsAreDeletedFromMainSource() {
        var mainJava = Path.of("src", "main", "java", "com", "xa", "mass", "task", "runtime");

        assertThat(DELETED_OLD_PORTS)
                .allSatisfy(port -> assertThat(mainJava.resolve(port + ".java")).doesNotExist());
    }

    @Test
    void oldCommandBucketDtosAreDeletedFromMainSource() {
        var mainJava = Path.of("src", "main", "java", "com", "xa", "mass", "task", "runtime");

        assertThat(DELETED_OLD_COMMAND_BUCKETS)
                .allSatisfy(command -> assertThat(mainJava.resolve(command + ".java")).doesNotExist());
    }

    private static void collectForbiddenSnippetViolations(Path path, List<String> violations) {
        try {
            var source = Files.readString(path);
            for (String forbidden : FORBIDDEN_SOURCE_SNIPPETS) {
                if (source.contains(forbidden)) {
                    violations.add(path + " contains " + forbidden);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }

    private static void collectForbiddenTypeViolation(Class<?> port, Class<?> type, List<String> violations) {
        var canonicalName = type.getCanonicalName();
        if (canonicalName == null) {
            return;
        }
        for (String forbiddenPrefix : FORBIDDEN_TYPE_PREFIXES) {
            if (canonicalName.startsWith(forbiddenPrefix)) {
                violations.add(port.getName() + " exposes " + canonicalName);
            }
        }
    }
}
