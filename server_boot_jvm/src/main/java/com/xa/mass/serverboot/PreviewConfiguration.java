package com.xa.mass.serverboot;

import com.xa.mass.scenario.sms.SmsScenarioConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Import;

/** Preview's SMS composition; finite business scenarios have independent explicit switches. */
@Configuration(proxyBeanMethods = false)
@Profile("preview")
@Import(SmsScenarioConfiguration.class)
public class PreviewConfiguration {
    @Bean("scenarioWorkerGroup")
    String workerGroup() {
        return "demo-sim";
    }

}
