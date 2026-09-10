package com.xa.mass.distribution;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Distribution page composition; SMS API and jobs remain profile-owned. */
@Configuration(proxyBeanMethods = false)
public class ConsoleFrontendConfiguration implements WebMvcConfigurer {
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        for (String path : new String[]{"/sms", "/sms/", "/sms/listeners", "/sms/listeners/",
                "/sms/metrics", "/sms/metrics/"}) {
            registry.addViewController(path).setViewName("forward:/index.html");
        }
    }
}
