package com.xa.mass.server.scenariorpc;

import com.xa.mass.scenariorpc.ScenarioRpcEngine;
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
    ScenarioRpcLoopbackClient scenarioRpcLoopbackClient(
            ScenarioRpcProperties properties
    ) {
        return new ScenarioRpcLoopbackClient(properties);
    }

    @Bean
    ScenarioRpcService scenarioRpcService(
            ScenarioRpcEngine engine,
            ScenarioRpcFileStore files,
            ScenarioRpcLoopbackClient rpc
    ) {
        return new ScenarioRpcService(engine, files, rpc, Clock.systemUTC());
    }
}
