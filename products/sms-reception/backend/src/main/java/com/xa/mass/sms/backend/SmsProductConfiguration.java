package com.xa.mass.sms.backend;

import com.xa.mass.server.worker.group.WorkerGroupRegistrationService;
import com.xa.mass.server.task.call.TaskCallSubmissionService;
import com.xa.mass.server.task.TaskDataService;
import org.springframework.context.annotation.*;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@Profile("sms-reception")
@Import({ProductController.class, SmsFrontendController.class})
public class SmsProductConfiguration implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/sms/**").addResourceLocations("classpath:/sms-frontend/");
    }

    @Bean(destroyMethod = "close")
    ListenerService listenerService(WorkerGroupRegistrationService registrations,
            TaskCallSubmissionService submissions, TaskDataService results) {
        return new ListenerService(registrations, submissions, results);
    }
}
