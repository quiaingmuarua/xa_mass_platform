package com.xa.mass.serverboot;

import com.xa.mass.scenario.sms.SmsScenarioConfiguration;
import com.xa.mass.scenario.messages.MessageCampaignsScenarioConfiguration;
import com.xa.mass.scenario.appchecks.AppCheckScenarioConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Import;

/** Fixed business scenarios share one platform; App checks use their own Worker Groups. */
@Configuration(proxyBeanMethods = false)
@Profile("preview")
@Import({SmsScenarioConfiguration.class, MessageCampaignsScenarioConfiguration.class, AppCheckScenarioConfiguration.class})
public class PreviewConfiguration {
    @Bean("scenarioWorkerGroup")
    String workerGroup() {
        return "demo-sim";
    }

}
