package com.xa.mass.kernel.pacer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class KernelPacerModuleBoundaryTest {

    private static final Pattern TOP_LEVEL_PUBLIC_TYPE = Pattern.compile(
            "(?m)^public (?:(?:abstract|final|sealed|non-sealed) )*"
                    + "(?:class|record|enum|interface) "
                    + "([A-Za-z0-9_]+)"
    );
    private static final Pattern PACKAGE_DECLARATION = Pattern.compile(
            "(?m)^package ([A-Za-z0-9_.]+);$"
    );
    private static final String PACER_IMPORT =
            "import com.xa.mass.kernel.pacer.";

    @Test
    void exposesOnlyTheFiniteRuntimeEntry() throws IOException {
        Path sourceRoot = repositoryRoot().resolve(
                "kernel_pacer_jvm/src/main/java"
        );
        List<String> publicTypes = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        Matcher matcher = TOP_LEVEL_PUBLIC_TYPE.matcher(
                                read(path)
                        );
                        while (matcher.find()) {
                            publicTypes.add(matcher.group(1));
                        }
                    });
        }

        assertEquals(List.of("KernelPacerRuntime"), publicTypes);
    }

    @Test
    void keepsDeclaredPackagesAlignedWithSourcePaths() throws IOException {
        Path moduleRoot = repositoryRoot().resolve("kernel_pacer_jvm/src");

        assertPackagePaths(moduleRoot.resolve("main/java"));
        assertPackagePaths(moduleRoot.resolve("test/java"));
    }

    @Test
    void keepsTheStableDependencyDirectionAndSpringOut() throws IOException {
        Path root = repositoryRoot();
        String kernelBuild = read(root.resolve("kernel_jvm/build.gradle"));
        String pacerBuild = read(
                root.resolve("kernel_pacer_jvm/build.gradle")
        );
        String serverBuild = read(root.resolve("server_jvm/build.gradle"));

        assertFalse(kernelBuild.contains("kernel_pacer_jvm"));
        assertTrue(pacerBuild.contains("api project(':kernel_jvm')"));
        assertFalse(pacerBuild.contains("spring"));
        assertFalse(pacerBuild.contains("maven-publish"));
        assertFalse(pacerBuild.contains("publishing"));
        assertTrue(serverBuild.contains(
                "implementation project(':kernel_pacer_jvm')"
        ));

        try (Stream<Path> files = Files.walk(root.resolve(
                "kernel_pacer_jvm/src/main/java"
        ))) {
            assertTrue(files.filter(path -> path.toString().endsWith(".java"))
                    .map(KernelPacerModuleBoundaryTest::read)
                    .noneMatch(source -> source.contains("org.springframework")
                            || source.contains("io.lettuce")
                            || source.contains("RedisClient")
                            || source.contains("RedisKeyspace")));
        }

        List<String> directConsumers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(path -> path.getFileName().toString()
                            .equals("build.gradle"))
                    .filter(path -> !path.toString().contains(
                            java.io.File.separator + "build"
                                    + java.io.File.separator
                    ))
                    .filter(path -> read(path).contains(
                            "project(':kernel_pacer_jvm')"
                    ))
                    .map(root::relativize)
                    .map(Path::toString)
                    .map(path -> path.replace('\\', '/'))
                    .forEach(directConsumers::add);
        }
        assertEquals(List.of("server_jvm/build.gradle"), directConsumers);
    }

    @Test
    void serverImportsOnlyTheRuntimeFromPacerModule() throws IOException {
        Path sourceRoot = repositoryRoot().resolve(
                "server_jvm/src/main/java"
        );
        List<String> pacerImports = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .map(KernelPacerModuleBoundaryTest::read)
                    .flatMap(source -> source.lines())
                    .map(String::trim)
                    .filter(line -> line.startsWith(PACER_IMPORT))
                    .forEach(pacerImports::add);
        }

        assertEquals(Set.of(
                "import com.xa.mass.kernel.pacer.KernelPacerRuntime;"
        ), Set.copyOf(pacerImports));
    }

    private static Path repositoryRoot() {
        return Path.of(System.getProperty("xa.mass.repository.root"));
    }

    private static void assertPackagePaths(Path sourceRoot)
            throws IOException {
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        Path relative = sourceRoot.relativize(path);
                        String expectedPackage = relative.getParent()
                                .toString()
                                .replace(java.io.File.separatorChar, '.');
                        Matcher matcher = PACKAGE_DECLARATION.matcher(
                                read(path)
                        );
                        assertTrue(
                                matcher.find(),
                                () -> "missing package declaration: "
                                        + relative
                        );
                        assertEquals(
                                expectedPackage,
                                matcher.group(1),
                                () -> "package/path mismatch: " + relative
                        );
                    });
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException error) {
            throw new IllegalStateException("Could not read " + path, error);
        }
    }
}
