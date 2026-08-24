package com.xa.mass.server.workerassembly;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ServerWorkerAssemblyArchitectureTest {

    @Test
    void serverAssemblyOnlyComposesConfiguredGroupsAndAdapters()
            throws Exception {
        Path root = Path.of(
                "src/main/java/com/xa/mass/server/workerassembly"
        );
        String sources;
        try (Stream<Path> paths = Files.walk(root)) {
            StringBuilder combined = new StringBuilder();
            paths.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .forEach(path -> {
                        try {
                            combined.append(Files.readString(path));
                        } catch (Exception error) {
                            throw new IllegalStateException(error);
                        }
                    });
            sources = combined.toString();
        }

        assertThat(sources)
                .contains("properties.groupConfigJson()")
                .contains("WorkerGroupRegistrationService")
                .contains("groupInitializer.initialize()")
                .contains("adapterManager.start()")
                .contains("adapterManager.close()");
        assertThat(sources)
                .doesNotContain("ScenarioWorkerBundle")
                .doesNotContain("ScenarioWorkerBundleConfig")
                .doesNotContain("ScenarioWorkerBundles")
                .doesNotContain("ScenarioWorkers")
                .doesNotContain("capabilityAssemblyJson")
                .doesNotContain("sandboxRoot")
                .doesNotContain("runtimeApiBaseUrl")
                .doesNotContain("PHONE_NUMBER")
                .doesNotContain("STRING_UTILS")
                .doesNotContain("workerIdPrefix")
                .doesNotContain("workerCount")
                .doesNotContain("PhoneNumberCapability")
                .doesNotContain("StringUtilityCapability")
                .doesNotContain("WorkerEventDefinition")
                .doesNotContain("WorkerEventHandler")
                .doesNotContain("OkHttpTextWebSocketClient")
                .doesNotContain("com.google.i18n.phonenumbers")
                .doesNotContain("io.lettuce")
                .doesNotContain("kernelredis")
                .doesNotContain("ScoreBand")
                .doesNotContain("Pacer")
                .doesNotContain("ResourceCommandController")
                .doesNotContain("WorkerResourceCatalog")
                .doesNotContain("TaskResourceCatalog")
                .doesNotContain("TaskRuntime")
                .doesNotContain("RuntimeApiHttpClient")
                .doesNotContain("sandboxDirectory")
                .doesNotContain("java.nio.file")
                .doesNotContain("Class.forName")
                .doesNotContain("java.lang.reflect")
                .doesNotContain("ServiceLoader");
    }
}
