package com.xa.mass.scenario.appchecks;

import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.error.ServerErrorCode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppCheckAccessTest {
    @Test void refusesConfigurationBeforeStorageWhileCatalogRemainsDiscovery() {
        var tasks = mock(AppCheckTaskService.class);
        var store = mock(AppCheckConfigurationStore.class);
        var controller = new AppCheckController(tasks, store, org.mockito.Mockito.mock(AppCheckCredits.class));
        var operator = com.xa.mass.server.testing.TaskCallers.caller("outside1", "operator", "app-checks");
        assertThatThrownBy(() -> controller.configuration(operator)).isInstanceOfSatisfying(ServerException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ServerErrorCode.TASK_ACCESS_DENIED));
        assertThatThrownBy(() -> controller.saveConfiguration(operator, Map.of())).isInstanceOf(ServerException.class);
        verifyNoInteractions(store, tasks);
        controller.configuration(com.xa.mass.server.testing.TaskCallers.caller("admin123", "admin"));
        verify(store).view();
        controller.catalog();
        verify(tasks).catalog();
    }
}
