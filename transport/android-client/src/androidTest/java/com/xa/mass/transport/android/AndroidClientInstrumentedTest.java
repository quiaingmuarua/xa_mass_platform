package com.xa.mass.transport.android;

import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.xa.mass.transport.android.websocket.AndroidWebSocketWorker;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AndroidClientInstrumentedTest {

    @Test
    public void completeWorkerStartsAndStopsOnAndroidRuntime() {
        AndroidWebSocketWorker worker = new AndroidWebSocketWorker(
                URI.create("ws://127.0.0.1:1/worker"),
                "instrumented-worker",
                Duration.ofSeconds(1),
                Duration.ofMillis(50),
                List.of(WorkerEventDefinition.of(
                        "TASK",
                        "test.observe",
                        WorkerEventParameterResolvers.string(),
                        value -> value
                ))
        );

        worker.start();
        worker.close();

        assertTrue(!worker.isConnected());
    }
}
