package com.xa.mass.server.workerassembly;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.scenarioworkers.ScenarioWorkerBundle;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ServerWorkerBundleManagerTest {

    @Test
    void startsInDeclarationOrderAndClosesInReverseOrder() {
        ScenarioWorkerBundle phone = bundle("phone-number");
        ScenarioWorkerBundle strings = bundle("string-utils");
        ServerWorkerBundleManager manager =
                new ServerWorkerBundleManager(
                        List.of(phone, strings)
                );

        manager.start();
        manager.start();
        manager.close();
        manager.close();

        InOrder order = inOrder(phone, strings);
        order.verify(phone).start();
        order.verify(strings).start();
        order.verify(strings).close();
        order.verify(phone).close();
        verify(phone, times(1)).start();
        verify(strings, times(1)).start();
    }

    @Test
    void secondBundleFailureClosesAllBundlesInReverseOrder() {
        ScenarioWorkerBundle phone = bundle("phone-number");
        ScenarioWorkerBundle strings = bundle("string-utils");
        RuntimeException failure = new RuntimeException("failed");
        doThrow(failure).when(strings).start();
        ServerWorkerBundleManager manager =
                new ServerWorkerBundleManager(
                        List.of(phone, strings)
                );

        assertThatThrownBy(manager::start).isSameAs(failure);

        InOrder order = inOrder(phone, strings);
        order.verify(phone).start();
        order.verify(strings).start();
        order.verify(strings).close();
        order.verify(phone).close();
    }

    private static ScenarioWorkerBundle bundle(String bundleId) {
        ScenarioWorkerBundle bundle = mock(
                ScenarioWorkerBundle.class
        );
        when(bundle.bundleId()).thenReturn(bundleId);
        return bundle;
    }
}
