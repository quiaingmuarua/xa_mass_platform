package com.xa.mass.serverboot;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Distribution page composition; product APIs and jobs remain profile-owned. */
@Configuration(proxyBeanMethods = false)
public class ConsoleFrontendConfiguration implements WebMvcConfigurer {
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        for (String path : new String[]{"/sms", "/sms/", "/sms/listeners", "/sms/listeners/",
                "/sms/metrics", "/sms/metrics/", "/messages", "/messages/",
                "/messages/tasks/{taskId}", "/messages/tasks/{taskId}/",
                "/app-checks", "/app-checks/", "/app-checks/tasks/{taskId}", "/app-checks/tasks/{taskId}/"}) {
            registry.addViewController(path).setViewName("forward:/index.html");
        }
    }
}
