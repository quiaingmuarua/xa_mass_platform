package com.xa.mass.springserver;

import com.xa.mass.server.XaMassServerConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/** Explicit executable composition; platform and scenarios retain their own services. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import({XaMassServerConfiguration.class, PreviewConfiguration.class, ConsoleFrontendConfiguration.class})
public class SpringServerConfiguration {}
