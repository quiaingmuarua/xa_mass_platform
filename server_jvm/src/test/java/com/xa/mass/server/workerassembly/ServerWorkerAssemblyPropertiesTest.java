package com.xa.mass.server.workerassembly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerPropertyIndexRuntime;
import com.xa.mass.kernel.worker.WorkerRuntime;
import com.xa.mass.server.workerdelivery.adapter
        .ServerWorkerDeliveryAdapterConfiguration;
import com.xa.mass.workerdelivery.adapter.application
        .WorkerDeliveryAdapterManager;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context
        .ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner
        .ApplicationContextRunner;

class ServerWorkerAssemblyPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            ServerWorkerDeliveryAdapterConfiguration.class,
                            ServerWorkerAssemblyConfiguration.class
                    )
                    .withBean(
                            WorkerResourceCatalog.class,
                            () -> mock(WorkerResourceCatalog.class)
                    )
                    .withBean(
                            WorkerRuntime.class,
                            () -> mock(WorkerRuntime.class)
                    )
                    .withBean(
                            WorkerPropertyIndexRuntime.class,
                            () -> mock(WorkerPropertyIndexRuntime.class)
                    );

    @Test
    void absentBundlesCreateNoBuiltInWorkers() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(
                    ServerWorkerBundleManager.class
            ).bundleIds()).isEmpty();
        });
    }

    @Test
    void defaultConfigIsInertAndScenarioProfileIsExplicit() {
        ApplicationContextRunner configDataRunner = contextRunner
                .withInitializer(
                        new ConfigDataApplicationContextInitializer()
                );
        configDataRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(
                    WorkerDeliveryAdapterManager.class
            ).adapters()).isEmpty();
            assertThat(context.getBean(
                    ServerWorkerBundleManager.class
            ).bundleIds()).isEmpty();
        });

        configDataRunner.withPropertyValues(
                "spring.profiles.active=scenario-workers"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(
                    WorkerDeliveryAdapterManager.class
            ).adapters()).containsOnlyKeys("scenario-websocket");
            assertThat(context.getBean(
                    ServerWorkerBundleManager.class
            ).bundleIds()).containsExactly(
                    "phone-number",
                    "string-utils"
            );
            ServerWorkerAssemblyProperties properties =
                    context.getBean(
                            ServerWorkerAssemblyProperties.class
                    );
            assertThat(
                    ServerWorkerAssemblyConfiguration
                            .requireWebSocketAdapter(
                                    "phone-number",
                                    properties.bundles().get(
                                            "phone-number"
                                    ),
                                    context.getBean(
                                            WorkerDeliveryAdapterManager.class
                                    )
                            )
            ).hasToString(
                    "ws://127.0.0.1:18083"
                            + "/api/v1/worker-delivery/websocket"
            );
            assertThat(properties.bundles())
                    .containsOnlyKeys("phone-number", "string-utils");
            assertThat(properties.bundles().get(
                    "phone-number"
            ).workerGroupId()).isEqualTo(
                    "scenario-phone-number-workers"
            );
            assertThat(properties.bundles().get(
                    "string-utils"
            ).workerGroupId()).isEqualTo(
                    "scenario-string-utils-workers"
            );
        });
    }

    @Test
    void bindsStrictPhoneNumberBundleWithBoundedDefaults() {
        contextRunner.withPropertyValues(validConfiguration())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ServerWorkerAssemblyProperties properties =
                            context.getBean(
                                    ServerWorkerAssemblyProperties.class
                            );
                    var bundle = properties.bundles().get(
                            "phone-number"
                    );
                    assertThat(bundle.workerCount()).isEqualTo(10);
                    assertThat(bundle.requestTimeout())
                            .isEqualTo(Duration.ofSeconds(10));
                    assertThat(bundle.reconnectInterval())
                            .isEqualTo(Duration.ofMillis(250));
                    assertThat(bundle.connectTimeout())
                            .isEqualTo(Duration.ofSeconds(15));
                    assertThat(context.getBean(
                            ServerWorkerBundleManager.class
                    ).bundleIds()).containsExactly("phone-number");
                });
    }

    @Test
    void rejectsUnknownFieldsAndInvalidBounds() {
        assertFailed(append(
                validConfiguration(),
                "xa.mass.worker-assembly.bundles"
                        + ".phone-number.unexpected=true"
        ));
        assertFailed(replace(
                validConfiguration(),
                ".worker-count=10",
                ".worker-count=101"
        ));
    }

    @Test
    void rejectsDuplicateGroupAndGeneratedWorkerIdentities() {
        assertFailed(append(
                validTwoBundleConfiguration(),
                        "xa.mass.worker-assembly.bundles"
                                + ".string-utils.worker-group-id="
                        + "scenario-phone-number-workers"
        ));
        assertFailed(replace(
                validTwoBundleConfiguration(),
                ".string-utils.worker-id-prefix="
                        + "string-utils-worker-",
                ".string-utils.worker-id-prefix="
                        + "scenario-phone-number-worker-"
        ));
    }

    @Test
    void rejectsMissingOrNonWebSocketAdapterReferences() {
        String[] missing = validConfiguration();
        missing = withoutAdapter(missing);
        assertFailed(missing);

        assertFailed(replace(
                validConfiguration(),
                ".websocket-1.type=WEBSOCKET",
                ".websocket-1.type=SOCKET"
        ));
    }

    private void assertFailed(String... properties) {
        contextRunner.withPropertyValues(properties)
                .run(context -> assertThat(context).hasFailed());
    }

    private static String[] validConfiguration() {
        return new String[]{
                "xa.mass.worker-delivery.adapter.instances"
                        + ".websocket-1.type=WEBSOCKET",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".websocket-1.listen-port=18083",
                "xa.mass.worker-assembly.bundles"
                        + ".phone-number.type=PHONE_NUMBER",
                "xa.mass.worker-assembly.bundles"
                        + ".phone-number.adapter-id=websocket-1",
                "xa.mass.worker-assembly.bundles"
                        + ".phone-number.worker-group-id="
                        + "scenario-phone-number-workers",
                "xa.mass.worker-assembly.bundles"
                        + ".phone-number.worker-id-prefix="
                        + "scenario-phone-number-worker-",
                "xa.mass.worker-assembly.bundles"
                        + ".phone-number.worker-count=10"
        };
    }

    private static String[] validTwoBundleConfiguration() {
        return append(
                append(
                        append(
                                append(
                                        validConfiguration(),
                                        "xa.mass.worker-assembly.bundles"
                                                + ".string-utils.type="
                                                + "STRING_UTILS"
                                ),
                                "xa.mass.worker-assembly.bundles"
                                        + ".string-utils.adapter-id="
                                        + "websocket-1"
                        ),
                        "xa.mass.worker-assembly.bundles"
                                + ".string-utils.worker-group-id="
                                + "string-utils-workers"
                ),
                "xa.mass.worker-assembly.bundles"
                        + ".string-utils.worker-id-prefix="
                        + "string-utils-worker-"
        );
    }

    private static String[] append(
            String[] values,
            String extra
    ) {
        String[] result = new String[values.length + 1];
        System.arraycopy(values, 0, result, 0, values.length);
        result[values.length] = extra;
        return result;
    }

    private static String[] replace(
            String[] values,
            String oldSuffix,
            String newSuffix
    ) {
        String[] result = values.clone();
        for (int index = 0; index < result.length; index++) {
            if (result[index].endsWith(oldSuffix)) {
                result[index] = result[index].substring(
                        0,
                        result[index].length() - oldSuffix.length()
                ) + newSuffix;
                return result;
            }
        }
        throw new IllegalArgumentException(
                "Could not replace property suffix " + oldSuffix
        );
    }

    private static String[] withoutAdapter(String[] values) {
        return java.util.Arrays.stream(values)
                .filter(value -> !value.contains(
                        "worker-delivery.adapter.instances"
                ))
                .toArray(String[]::new);
    }
}
