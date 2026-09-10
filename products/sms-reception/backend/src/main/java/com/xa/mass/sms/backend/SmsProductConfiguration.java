package com.xa.mass.sms.backend;

import com.xa.mass.server.worker.group.WorkerGroupRegistrationService;
import com.xa.mass.server.task.call.TaskCallSubmissionService;
import com.xa.mass.server.task.TaskDataService;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.List;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@Profile("sms-reception")
@Import(ProductController.class)
public class SmsProductConfiguration {
    @Bean(destroyMethod = "close")
    ListenerService listenerService(WorkerGroupRegistrationService registrations,
            TaskCallSubmissionService submissions, TaskDataService results,
            @Qualifier("productCountryGroups") Map<String, String> groups,
            @Qualifier("productWorkerEvents") List<String> events) {
        return new ListenerService(registrations, submissions, results, groups, events);
    }
}
