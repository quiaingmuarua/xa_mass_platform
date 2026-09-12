package com.xa.mass.messages.backend;

import com.xa.mass.server.task.TaskCreationService;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.TaskLifecycleService;
import com.xa.mass.server.worker.group.WorkerGroupRegistrationService;
import java.util.List;
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
            @Qualifier("productWorkerGroup") String workerGroupId,
            @Qualifier("productWorkerEvents") List<String> events) {
        return new CampaignService(registrations, creation, data, lifecycle, workerGroupId, events);
    }
}
