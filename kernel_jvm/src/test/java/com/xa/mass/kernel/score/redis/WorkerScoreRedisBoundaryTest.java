package com.xa.mass.kernel.score.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import io.lettuce.core.api.sync.RedisCommands;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.Modifier;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

class WorkerScoreRedisBoundaryTest {
    @Test
    void callersCannotUseDecodedWorkerCoordinatesOrArithmetic() throws Exception {
        var core = com.xa.mass.kernel.score.WorkerScoreCore.class;
        assertEquals(Set.of("MAX_SCORE_BATCH_SIZE", "MAX_REGISTRATION_BATCH_SIZE", "MAX_REGISTERED_WORKER_SAMPLE_LIMIT"),
                Arrays.stream(core.getDeclaredFields()).map(java.lang.reflect.Field::getName).collect(Collectors.toSet()));
        assertFalse(Arrays.stream(core.getDeclaredClasses()).anyMatch(type ->
                type.getSimpleName().equals("WorkerScoreState") || type.getSimpleName().equals("WorkerScoreObservation")));
        var sources = new ArrayList<Path>();
        for (String module : List.of("kernel_pacer_jvm", "server_jvm", "worker_matching_jvm")) {
            try (var paths = Files.walk(Path.of("..", module, "src/main/java"))) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(path);
                    assertFalse(source.contains("WorkerScoreEncoding"), path.toString());
                    assertFalse(source.contains("WorkerScoreState"), path.toString());
                    assertFalse(source.contains("WorkerScoreObservation"), path.toString());
                    if (source.contains("import com.xa.mass.kernel.score.WorkerScoreCore")
                            || source.contains("import com.xa.mass.kernel.assignment.WorkerMatching")) sources.add(path);
                }
            }
        }
        var violations = new ArrayList<String>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var files = compiler.getStandardFileManager(null, null, null)) {
            var task = (JavacTask) compiler.getTask(null, files, null, List.of("-proc:none"), null,
                    files.getJavaFileObjectsFromPaths(sources));
            for (var unit : task.parse()) {
                new TreeScanner<Void, Void>() {
                    @Override public Void visitBinary(com.sun.source.tree.BinaryTree expression, Void unused) {
                        if (!Set.of(com.sun.source.tree.Tree.Kind.EQUAL_TO,
                                com.sun.source.tree.Tree.Kind.NOT_EQUAL_TO,
                                com.sun.source.tree.Tree.Kind.CONDITIONAL_AND,
                                com.sun.source.tree.Tree.Kind.CONDITIONAL_OR).contains(expression.getKind())
                                && usesScore(expression)) violations.add(unit.getSourceFile().getName() + ": " + expression);
                        return super.visitBinary(expression, unused);
                    }
                    @Override public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
                        if (Set.of("Math.abs", "Long.signum").contains(call.getMethodSelect().toString())
                                && usesScore(call)) violations.add(unit.getSourceFile().getName() + ": " + call);
                        return super.visitMethodInvocation(call, unused);
                    }
                }.scan(unit, null);
            }
        }
        assertEquals(List.of(), violations);
    }

    private static boolean usesScore(com.sun.source.tree.Tree expression) {
        boolean[] found = {false};
        new TreeScanner<Void, Void>() {
            @Override public Void visitIdentifier(com.sun.source.tree.IdentifierTree id, Void unused) {
                if (Set.of("score", "workerScore", "observedScore", "observedHotScore", "heldScore")
                        .contains(id.getName().toString())) found[0] = true;
                return null;
            }
            @Override public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
                if (call.getMethodSelect() instanceof MemberSelectTree select
                        && select.getIdentifier().contentEquals("score")) found[0] = true;
                return super.visitMethodInvocation(call, unused);
            }
        }.scan(expression, null);
        return found[0];
    }

    @Test
    void publicCombinationsHaveNoRedisCommandOrConnectionAccess() throws Exception {
        Path source = Path.of("src/main/java/com/xa/mass/kernel/score/redis/RedisWorkerScoreCore.java");
        // Inspect executable calls, not private helper names or a script line-count target.
        Set<String> dataCommands = Arrays.stream(RedisCommands.class.getMethods())
                .map(java.lang.reflect.Method::getName)
                .filter(name -> name.startsWith("z") || name.startsWith("eval")
                        || name.equals("time") || name.equals("dispatch"))
                .collect(Collectors.toSet());
        dataCommands.addAll(List.of("connect", "sync", "async", "reactive"));
        List<String> violations = new ArrayList<>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var files = compiler.getStandardFileManager(null, null, null)) {
            var task = (JavacTask) compiler.getTask(null, files, null, List.of("-proc:none"), null,
                    files.getJavaFileObjects(source.toFile()));
            for (var unit : task.parse()) {
                new TreeScanner<Void, Void>() {
                    @Override public Void visitMethod(MethodTree method, Void unused) {
                        if (!method.getModifiers().getFlags().contains(Modifier.PUBLIC)) return null;
                        new TreeScanner<Void, Void>() {
                            @Override public Void visitMethodInvocation(MethodInvocationTree call, Void ignored) {
                                if (call.getMethodSelect() instanceof MemberSelectTree select
                                        && dataCommands.contains(select.getIdentifier().toString())) {
                                    violations.add(method.getName() + ": " + call);
                                }
                                return super.visitMethodInvocation(call, ignored);
                            }
                        }.scan(method.getBody(), null);
                        return null;
                    }
                }.scan(unit, null);
            }
        }
        assertEquals(List.of(), violations);
        String encoding = Files.readString(source.resolveSibling("WorkerScoreEncoding.java"));
        assertFalse(encoding.contains("io.lettuce"));
        assertFalse(encoding.contains("redis.call"));
    }
}
