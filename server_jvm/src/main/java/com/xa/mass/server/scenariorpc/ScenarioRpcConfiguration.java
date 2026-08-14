package com.xa.mass.server.scenariorpc;

import com.xa.mass.scenariorpc.ScenarioRpcEngine;
import com.xa.mass.server.taskdata.TaskDataService;
import com.xa.mass.server.taskdata.WorkerGroupTaskCatalog;
import java.io.IOException;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Configuration(proxyBeanMethods = false)
@Profile("scenario-workers")
@EnableConfigurationProperties(ScenarioRpcProperties.class)
public class ScenarioRpcConfiguration {

    @Bean
    ScenarioRpcEngine scenarioRpcEngine() {
        return ScenarioRpcEngine.create();
    }

    @Bean
    ScenarioRpcFileStore scenarioRpcFileStore(
            ScenarioRpcProperties properties
    ) throws IOException {
        return ScenarioRpcFileStore.open(properties);
    }

    @Bean
    Clock scenarioRpcClock() {
        return Clock.systemUTC();
    }

    @Bean
    ScenarioRpcTaskBatchExchange scenarioRpcTaskBatchExchange(
            TaskDataService taskData,
            WorkerGroupTaskCatalog taskCatalog,
            Clock scenarioRpcClock
    ) {
        return new ScenarioRpcTaskBatchExchange(
                taskData,
                taskCatalog,
                scenarioRpcClock
        );
    }

    @Bean
    ScenarioRpcInstanceRegistry scenarioRpcInstanceRegistry(
            ScenarioRpcProperties properties
    ) {
        return new ScenarioRpcInstanceRegistry(properties.maxScenarios());
    }

    @Bean
    ScenarioRpcService scenarioRpcService(
            ScenarioRpcEngine engine,
            ScenarioRpcFileStore files,
            ScenarioRpcTaskBatchExchange exchange,
            ScenarioRpcInstanceRegistry instances,
            ScenarioRpcProperties properties,
            Clock scenarioRpcClock
    ) {
        return new ScenarioRpcService(
                engine,
                files,
                exchange,
                instances,
                properties,
                scenarioRpcClock
        );
    }
}
