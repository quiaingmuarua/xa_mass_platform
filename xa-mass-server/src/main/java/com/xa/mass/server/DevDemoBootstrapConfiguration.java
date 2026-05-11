package com.xa.mass.server;

import com.xa.mass.sdk.MassBootstrapDataProvider;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.SubmitterRegistration;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ProjectMetadata;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.server.bootstrap.DevDemoBootstrapDataProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.List;
import java.util.Map;

/**
 * Mainline dev-shell bootstrap for a richer default demo environment.
 */
@Configuration
@Profile("dev")
@ConditionalOnProperty(prefix = "mass.demo.bootstrap", name = "enabled", havingValue = "true")
public class DevDemoBootstrapConfiguration {

    private static final List<String> DEMO_EVENT_CODES = List.of("demo.dispatch", "demo.dispatch.gb");

    @Value("${mass.demo.bootstrap.worker-count:36}")
    private int workerCount;

    @Value("${mass.demo.bootstrap.task-count:12}")
    private int taskCount;

    @Value("${mass.demo.bootstrap.items-per-task:1500}")
    private int itemsPerTask;

    @Value("${mass.demo.bootstrap.batch-size:20}")
    private int batchSize;

    @Value("${mass.demo.bootstrap.default-max-retry-count:1}")
    private int defaultMaxRetryCount;

    @Value("${mass.demo.bootstrap.auto-approve-tasks:true}")
    private boolean autoApproveTasks;

    @Value("${mass.demo.bootstrap.routing-lanes:us,gb,de,fr,sg,jp}")
    private List<String> routingLanes;

    @Bean
    @ConditionalOnMissingBean(MassBootstrapDataProvider.class)
    public MassBootstrapDataProvider devDemoBootstrapDataProvider() {
        return new DevDemoBootstrapDataProvider(
                workerCount,
                taskCount,
                itemsPerTask,
                batchSize,
                defaultMaxRetryCount,
                autoApproveTasks,
                routingLanes
        );
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public CommandLineRunner devDemoCatalogBootstrapRunner(MassSdkApplication app,
                                                           ObjectProvider<MassBootstrapDataProvider> bootstrapProvider) {
        return args -> {
            if (!hasDevDemoBootstrapProvider(bootstrapProvider)) {
                return;
            }
            app.registerEventDefinition(EventDefinition.builder()
                    .code("demo.dispatch")
                    .name("Demo Dispatch")
                    .description("Default dev-shell demo dispatch event.")
                    .payloadTypes(List.of(PayloadType.JSON))
                    .taskModes(List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                    .projectCodes(List.of("demoApp", "demoOps"))
                    .build());
            app.registerEventDefinition(EventDefinition.builder()
                    .code("demo.dispatch.gb")
                    .name("Demo Dispatch GB")
                    .description("GB lane demo dispatch event.")
                    .payloadTypes(List.of(PayloadType.JSON))
                    .taskModes(List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                    .projectCodes(List.of("demoApp", "demoOps"))
                    .build());
            app.registerProject(ProjectMetadata.builder()
                    .code("demoApp")
                    .name("Demo App")
                    .description("Primary dev-shell demo project with active workload and approval flow.")
                    .eventCodes(DEMO_EVENT_CODES)
                    .build());
            app.registerProject(ProjectMetadata.builder()
                    .code("demoOps")
                    .name("Demo Ops")
                    .description("Secondary dev-shell demo project for operator and cross-project API checks.")
                    .eventCodes(DEMO_EVENT_CODES)
                    .build());
        };
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public CommandLineRunner devDemoSubmitterBootstrapRunner(MassSdkApplication app,
                                                             ObjectProvider<MassBootstrapDataProvider> bootstrapProvider) {
        return args -> {
            if (!hasDevDemoBootstrapProvider(bootstrapProvider)) {
                return;
            }
            app.registerSubmitter(SubmitterRegistration.builder()
                    .principalId("demo-app-submitter")
                    .credential("demo-app-key")
                    .userId("demo-app-user")
                    .projectScope("demoApp")
                    .eventScopes(DEMO_EVENT_CODES)
                    .attributes(Map.of("label", "Demo App Submitter"))
                    .build());
            app.registerSubmitter(SubmitterRegistration.builder()
                    .principalId("demo-ops-submitter")
                    .credential("demo-ops-key")
                    .userId("demo-ops-user")
                    .projectScope("demoOps")
                    .eventScopes(DEMO_EVENT_CODES)
                    .attributes(Map.of("label", "Demo Ops Submitter"))
                    .build());
            app.registerSubmitter(SubmitterRegistration.builder()
                    .principalId("demo-admin-submitter")
                    .credential("demo-admin-key")
                    .userId("demo-admin")
                    .permissions(List.of(PrincipalContext.WILDCARD_SCOPE))
                    .projectScopes(List.of("demoApp", "demoOps"))
                    .eventScopes(DEMO_EVENT_CODES)
                    .attributes(Map.of("label", "Demo Admin Submitter"))
                    .build());
        };
    }

    @Bean
    @Order(10)
    public CommandLineRunner devDemoDataLoadRunner(MassSdkApplication app,
                                                   ObjectProvider<MassBootstrapDataProvider> bootstrapProvider) {
        return args -> {
            MassBootstrapDataProvider provider = bootstrapProvider.getIfAvailable();
            if (provider instanceof DevDemoBootstrapDataProvider) {
                provider.loadInto(app);
            }
        };
    }

    private boolean hasDevDemoBootstrapProvider(ObjectProvider<MassBootstrapDataProvider> bootstrapProvider) {
        return bootstrapProvider.getIfAvailable() instanceof DevDemoBootstrapDataProvider;
    }
}
