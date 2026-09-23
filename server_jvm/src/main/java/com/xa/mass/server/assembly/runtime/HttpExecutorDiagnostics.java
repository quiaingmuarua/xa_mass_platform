package com.xa.mass.server.assembly.runtime;

import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Name;
import jdk.jfr.Period;
import jdk.jfr.StackTrace;
import org.apache.coyote.AbstractProtocol;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/** JFR owns the periodic callback. Server adds no sampling thread or HTTP endpoint. */
@Component
@ConditionalOnProperty(name = "xa.mass.diagnostics.enabled", havingValue = "true")
public final class HttpExecutorDiagnostics implements ApplicationListener<WebServerInitializedEvent>, DisposableBean {
    private Runnable sample;

    @Name("xa.mass.HttpExecutor") @Category("XA Mass") @Enabled(false) @StackTrace(false) @Period("1 s")
    public static final class Snapshot extends Event {
        public boolean platformPool;
        public int poolSize = -1;
        public int active = -1;
        public int queued = -1;
        public int maximum = -1;
        public int minimum = -1;
    }

    @Override public synchronized void onApplicationEvent(WebServerInitializedEvent event) {
        if (sample != null || !(event.getWebServer() instanceof TomcatWebServer server)) return;
        if (!(server.getTomcat().getConnector().getProtocolHandler() instanceof AbstractProtocol<?> protocol)) return;
        sample = () -> {
            try {
                var observation = new Snapshot();
                if (!observation.isEnabled()) return;
                if (protocol.getExecutor() instanceof ThreadPoolExecutor pool) {
                    observation.platformPool = true;
                    observation.poolSize = pool.getPoolSize();
                    observation.active = pool.getActiveCount();
                    observation.queued = pool.getQueue().size();
                    observation.maximum = pool.getMaximumPoolSize();
                    observation.minimum = pool.getCorePoolSize();
                }
                observation.commit();
            } catch (RuntimeException ignored) { /* Coverage gaps are reported by the diagnostic reader. */ }
        };
        try { FlightRecorder.addPeriodicEvent(Snapshot.class, sample); }
        catch (RuntimeException ignored) { sample = null; }
    }

    @Override public synchronized void destroy() {
        if (sample != null) {
            try { FlightRecorder.removePeriodicEvent(sample); }
            catch (RuntimeException ignored) { /* Shutdown remains bounded and independent of diagnostics. */ }
            sample = null;
        }
    }
}
