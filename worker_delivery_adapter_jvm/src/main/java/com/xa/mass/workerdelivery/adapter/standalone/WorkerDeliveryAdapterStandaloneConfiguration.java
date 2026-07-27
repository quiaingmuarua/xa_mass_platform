package com.xa.mass.workerdelivery.adapter.standalone;

import com.xa.mass.workerdelivery.adapter.WorkerDeliveryAdapterConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import(WorkerDeliveryAdapterConfiguration.class)
public class WorkerDeliveryAdapterStandaloneConfiguration {
}
