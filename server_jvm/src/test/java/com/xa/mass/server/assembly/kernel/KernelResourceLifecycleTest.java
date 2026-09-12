package com.xa.mass.server.assembly.kernel;

import com.xa.mass.kernel.score.redis.RedisTaskItemScoreBandCore;
import com.xa.mass.kernel.score.redis.RedisTaskScoreBandCore;
import com.xa.mass.kernel.score.redis.RedisWorkerScoreCore;
import com.xa.mass.kernel.task.redis.RedisTaskResourceCatalog;
import com.xa.mass.kernel.task.redis.RedisTaskRuntime;
import com.xa.mass.kernel.worker.redis.RedisWorkerResourceCatalog;
import com.xa.mass.server.assembly.redis.KernelRedisConfiguration;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KernelResourceLifecycleTest {
    private static AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext();
        TestPropertyValues.of("xa.mass.redis.url=redis://localhost:6379/15",
                "xa.mass.redis.scope=test_server_resources").applyTo(context);
        context.register(KernelRedisConfiguration.class, KernelOwnerAssemblyConfiguration.class);
        return context;
    }

    @Test void sharedOwnersCloseOnceBeforeTheirClient() {
        var client = mock(RedisClient.class);
        try (var clients = mockStatic(RedisClient.class);
             var scores = mockConstruction(RedisTaskScoreBandCore.class);
             var items = mockConstruction(RedisTaskItemScoreBandCore.class);
             var tasks = mockConstruction(RedisTaskRuntime.class);
             var catalogs = mockConstruction(RedisTaskResourceCatalog.class);
             var workers = mockConstruction(RedisWorkerScoreCore.class);
             var bindings = mockConstruction(RedisWorkerResourceCatalog.class);
             var context = context()) {
            clients.when(() -> RedisClient.create(any(RedisURI.class))).thenReturn(client);
            context.refresh();
            assertThat(context.getBeansOfType(RedisClient.class)).hasSize(1);
            assertThat(context.getBean(RedisTaskRuntime.class)).isSameAs(tasks.constructed().getFirst());
            context.close();
            context.close();
            var order = inOrder(scores.constructed().getFirst(),
                    items.constructed().getFirst(), tasks.constructed().getFirst(), catalogs.constructed().getFirst(),
                    workers.constructed().getFirst(), bindings.constructed().getFirst(), client);
            order.verify(bindings.constructed().getFirst()).close();
            order.verify(workers.constructed().getFirst()).close();
            order.verify(catalogs.constructed().getFirst()).close();
            order.verify(tasks.constructed().getFirst()).close();
            order.verify(items.constructed().getFirst()).close();
            order.verify(scores.constructed().getFirst()).close();
            order.verify(client).shutdown();
            order.verifyNoMoreInteractions();
        }
    }

    @Test void failedBeanCreationReleasesEarlierOwnersAndClient() {
        var client = mock(RedisClient.class);
        try (var clients = mockStatic(RedisClient.class);
             var scores = mockConstruction(RedisTaskScoreBandCore.class);
             var items = mockConstruction(RedisTaskItemScoreBandCore.class);
             var tasks = mockConstruction(RedisTaskRuntime.class, (mock, context) -> {
                 throw new IllegalStateException("construction failed");
             });
             var context = context()) {
            clients.when(() -> RedisClient.create(any(RedisURI.class))).thenReturn(client);
            assertThatThrownBy(context::refresh).isInstanceOf(RuntimeException.class);
            verify(items.constructed().getFirst()).close();
            verify(scores.constructed().getFirst()).close();
            verify(client).shutdown();
        }
    }
}
