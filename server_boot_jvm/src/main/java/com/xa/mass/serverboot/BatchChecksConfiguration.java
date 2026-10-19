package com.xa.mass.serverboot;

import com.xa.mass.scenario.appchecks.AppCheckScenarioConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Explicit business composition, independent of the consumer's Worker implementation. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "xa.mass.scenarios.app-checks", name = "enabled", havingValue = "true")
@Import(AppCheckScenarioConfiguration.class)
public class BatchChecksConfiguration { }
