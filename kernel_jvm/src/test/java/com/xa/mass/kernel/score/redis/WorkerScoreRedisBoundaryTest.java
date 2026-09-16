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
