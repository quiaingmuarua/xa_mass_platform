package com.xa.mass.sms.backend;

import com.xa.mass.server.worker.group.WorkerGroupRegistrationService;
import com.xa.mass.server.task.call.TaskCallSubmissionService;
import com.xa.mass.server.task.TaskDataService;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@Profile("sms-reception")
@Import(ProductController.class)
public class SmsProductConfiguration {
    @Bean(destroyMethod = "close")
    ListenerService listenerService(WorkerGroupRegistrationService registrations,
            TaskCallSubmissionService submissions, TaskDataService results) {
        return new ListenerService(registrations, submissions, results);
    }
}
