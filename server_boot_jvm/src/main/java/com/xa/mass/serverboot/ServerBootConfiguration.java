package com.xa.mass.serverboot;

import com.xa.mass.server.XaMassServerConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.xa.mass.scenario.appchecks.AppCheckConfigurationProperties;

/** Explicit executable composition; platform and scenarios retain their own services. */
@SpringBootConfiguration
@EnableAutoConfiguration
@EnableConfigurationProperties(AppCheckConfigurationProperties.class)
@Import({XaMassServerConfiguration.class, PreviewConfiguration.class, BatchChecksConfiguration.class, ConsoleFrontendConfiguration.class})
public class ServerBootConfiguration {}
