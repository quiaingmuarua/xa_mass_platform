package com.xa.mass.scenario.sms;

import com.xa.mass.server.task.access.TaskCaller;

import com.xa.mass.server.task.call.TaskRpcPreparationService;
import com.xa.mass.server.task.call.TaskRpcCallService;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.observation.TaskLeaseProjection;
import com.xa.mass.server.task.observation.TaskLeaseProjectionMetrics;
import com.xa.mass.workermatching.PartitionedLeasePoolDefinition;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Qualifier;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@Import(SmsController.class)
public class SmsScenarioConfiguration {
    @Bean(destroyMethod = "close")
    SmsReceptionService smsReceptionService(TaskRpcPreparationService preparations,
            TaskRpcCallService calls, TaskDataService results, TaskLeaseProjectionMetrics projections,
            @Qualifier("scenarioWorkerGroup") String workerGroupId) {
        return new SmsReceptionService(preparations, calls, results, projections, workerGroupId, Clock.systemUTC(),
                TaskCaller.forProjectService(new com.xa.mass.server.project.ProjectDefinition("sms", java.util.List.of(workerGroupId))));
    }
    @Bean PartitionedLeasePoolDefinition smsPoolDefinition() {
        return new PartitionedLeasePoolDefinition(SmsReceptionService.POOL, SmsReceptionService.FUNCTION,
                "phone", "country", SmsReceptionService.TEMPLATES.keySet());
    }
    @Bean TaskLeaseProjection smsLeaseProjection(@Qualifier("scenarioWorkerGroup") String workerGroupId) {
        return new TaskLeaseProjection(SmsReceptionService.rpcTaskId(workerGroupId), "sms", workerGroupId,
                SmsReceptionService.POOL, SmsReceptionService::leaseProjection);
    }
}
