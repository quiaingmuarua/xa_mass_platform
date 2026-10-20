package com.xa.mass.serverboot;

import com.xa.mass.scenario.messages.MessageCampaignsScenarioConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Explicit Messages assembly, independent of the consumer's Worker implementation. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "xa.mass.scenarios.messages", name = "enabled", havingValue = "true")
@Import(MessageCampaignsScenarioConfiguration.class)
public class MessagesConfiguration { }
