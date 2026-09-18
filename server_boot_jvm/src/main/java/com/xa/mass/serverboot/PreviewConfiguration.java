package com.xa.mass.serverboot;

import com.xa.mass.scenario.sms.SmsScenarioConfiguration;
import com.xa.mass.scenario.messages.MessageCampaignsScenarioConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Import;

/** The preview enables both scenarios with one fixed shared Worker assembly. */
@Configuration(proxyBeanMethods = false)
@Profile("preview")
@Import({SmsScenarioConfiguration.class, MessageCampaignsScenarioConfiguration.class})
public class PreviewConfiguration {
    @Bean("scenarioWorkerGroup")
    String workerGroup() {
        return "demo-sim";
    }

}
