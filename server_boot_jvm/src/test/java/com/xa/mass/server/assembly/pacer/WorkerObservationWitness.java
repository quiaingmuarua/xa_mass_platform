package com.xa.mass.server.assembly.pacer;

import com.xa.mass.kernel.pacer.KernelPacerRuntime.WorkerObservation;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.beans.factory.config.BeanPostProcessor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Bounded test-only source witness; keeps the actual async consumer and its lifecycle. */
public final class WorkerObservationWitness implements BeanPostProcessor {
    private final ConcurrentLinkedQueue<WorkerObservation> observations = new ConcurrentLinkedQueue<>();

    @Override public Object postProcessAfterInitialization(Object bean, String name) {
        if (!(bean instanceof WorkerObservationConsumer consumer)) return bean;
        var witnessed = spy(consumer);
        doAnswer(call -> {
            if (observations.size() >= 500) throw new AssertionError("Allocation observation witness overflow");
            observations.add(call.getArgument(0));
            return call.callRealMethod();
        }).when(witnessed).accept(any());
        return witnessed;
    }

    public List<WorkerObservation> snapshot() { return List.copyOf(observations); }
}
