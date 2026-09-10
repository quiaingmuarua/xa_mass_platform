package com.xa.mass.messages.backend;

import com.xa.mass.server.task.TaskCreationService;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.TaskLifecycleService;
import com.xa.mass.server.worker.group.WorkerGroupRegistrationService;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@Profile("message-campaigns")
@Import(MessageController.class)
public class MessageProductConfiguration {
    @Bean(destroyMethod = "close")
    CampaignService campaigns(WorkerGroupRegistrationService registrations, TaskCreationService creation,
            TaskDataService data, TaskLifecycleService lifecycle,
            @Qualifier("productCountryGroups") Map<String, String> groups,
            @Qualifier("productWorkerEvents") List<String> events,
            @Value("${xa.mass.messages.maximum-candidate-workers:100}") int candidates) {
        return new CampaignService(registrations, creation, data, lifecycle, groups, events, candidates);
    }
}
