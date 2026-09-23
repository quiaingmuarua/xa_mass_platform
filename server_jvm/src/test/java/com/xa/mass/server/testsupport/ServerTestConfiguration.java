package com.xa.mass.server.testsupport;

import com.xa.mass.server.XaMassServerConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Import;

/** Platform-only Boot infrastructure for Server tests and OpenAPI export. */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import(XaMassServerConfiguration.class)
public class ServerTestConfiguration {}
