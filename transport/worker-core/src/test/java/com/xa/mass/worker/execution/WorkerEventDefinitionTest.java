package com.xa.mass.worker.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.workerdelivery.json.Jsons;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerEventDefinitionTest {

    @Test
    void definitionOnlyCarriesIdentityResolverAndHandler()
            throws Exception {
        WorkerEventParameterResolver<Parameters> resolver =
                payload -> {
                    Map<String, Object> values =
                            Jsons.parseObject(payload);
                    return new Parameters(
                            (String) values.get("value")
                    );
                };
        WorkerEventHandler<Parameters> handler =
                parameters -> parameters.value();
        WorkerEventDefinition<Parameters> definition =
                WorkerEventDefinition.extension(
                        "test.observe",
                        resolver,
                        handler
                );

        assertEquals(
                "extension.worker.test.observe",
                definition.eventName()
        );
        assertSame(resolver, definition.parameterResolver());
        assertSame(handler, definition.handler());
        assertFalse(Arrays.stream(
                        WorkerEventDefinition.class
                                .getDeclaredMethods()
                )
                .map(Method::getName)
                .anyMatch(name -> name.equals("invoke")
                        || name.equals("execute")
                        || name.equals("dispatch")));
    }

    @Test
    void rejectsBlankIdentityFields() {
        WorkerEventParameterResolver<String> resolver = payload -> payload;
        WorkerEventHandler<String> handler = payload -> payload;

        assertThrows(
                IllegalArgumentException.class,
                () -> WorkerEventDefinition.extension(
                        "",
                        resolver,
                        handler
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkerEventDefinition.extension(
                        "platform.worker.test.observe",
                        resolver,
                        handler
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkerEventDefinition.extension(
                        "Test.Observe",
                        resolver,
                        handler
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkerEventDefinition.extension(
                        "test..observe",
                        resolver,
                        handler
                )
        );
    }

    @Test
    void platformFactoryIsPackagePrivateAndBuildsPlatformWorkerName() {
        WorkerEventDefinition<String> definition =
                WorkerEventDefinition.platform(
                        "probe",
                        payload -> payload,
                        payload -> payload
                );

        assertEquals("platform.worker.probe", definition.eventName());
        Method platform = Arrays.stream(
                        WorkerEventDefinition.class.getDeclaredMethods()
                )
                .filter(method -> method.getName().equals("platform"))
                .findFirst()
                .orElseThrow();
        assertFalse(Modifier.isPublic(platform.getModifiers()));
    }

    @Test
    void oldIdentityApiIsAbsent() {
        assertFalse(Arrays.stream(
                        WorkerEventDefinition.class.getDeclaredMethods()
                )
                .map(Method::getName)
                .anyMatch(name -> name.equals("of")
                        || name.equals("src")
                        || name.equals("eventCode")));
    }

    private static final class Parameters {

        private final String value;

        private Parameters(String value) {
            this.value = value;
        }

        private String value() {
            return value;
        }
    }
}
