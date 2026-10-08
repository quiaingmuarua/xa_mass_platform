package com.xa.mass.scenario.appchecks.benchmark;

import com.xa.mass.serverboot.ServerBootConfiguration;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import io.lettuce.core.RedisClient;
import io.lettuce.core.event.command.CommandListener;
import io.lettuce.core.event.command.CommandStartedEvent;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Benchmark-only launcher, compiled outside production artifacts against either pinned Boot JAR. */
public final class BenchmarkServer {
    private static final Measurements MEASUREMENTS = new Measurements();

    public static void main(String[] args) {
        var application = new SpringApplication(ServerBootConfiguration.class, Fixture.class);
        application.addInitializers(context -> context.getBeanFactory().addBeanPostProcessor(new Observer()));
        application.run(args);
    }

    @Configuration(proxyBeanMethods = false)
    static class Fixture {
        @Bean Counters counters() { return new Counters(); }
    }

    @RestController
    static class Counters {
        @PostMapping("/__benchmark/app-checks/start")
        public Map<String, Object> start() { MEASUREMENTS.start(); return Map.of("measuring", true); }

        @PostMapping("/__benchmark/app-checks/stop")
        public Map<String, Object> stop() throws InterruptedException { return MEASUREMENTS.stop(); }
    }

    static final class Measurement {
        long calls, elapsedNanos, maximumNanos, failures, factsReads;
        Map<String, Object> snapshot() {
            return Map.of("calls", calls, "totalNanos", elapsedNanos, "maxNanos", maximumNanos,
                    "failures", failures, "factsReads", factsReads);
        }
    }

    static final class Measurements {
        final ThreadLocal<String> phase = new ThreadLocal<>();
        final Map<String, Measurement> stages = new LinkedHashMap<>();
        boolean active;
        int pending;
        Measurements() { for (String name : new String[]{"refill", "take", "outside"}) stages.put(name, new Measurement()); }
        synchronized void start() {
            if (active || pending != 0) throw new IllegalStateException("measurement already active");
            stages.replaceAll((name, old) -> new Measurement());
            active = true;
        }
        synchronized long begin() { if (!active) return 0; pending++; return System.nanoTime(); }
        synchronized void complete(String name, long started, boolean failed) {
            var stage = stages.get(name);
            long elapsed = Math.max(0, System.nanoTime() - started);
            stage.calls++; stage.elapsedNanos += elapsed; stage.maximumNanos = Math.max(stage.maximumNanos, elapsed);
            if (failed) stage.failures++;
            pending--; notifyAll();
        }
        synchronized void factsRead() {
            if (!active && phase.get() == null) return;
            stages.get(phase.get() == null ? "outside" : phase.get()).factsReads++;
        }
        synchronized Map<String, Object> stop() throws InterruptedException {
            active = false;
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (pending > 0 && System.nanoTime() < deadline) wait(10);
            if (pending != 0) throw new IllegalStateException("unfinished measurement calls");
            var result = new LinkedHashMap<String, Object>();
            stages.forEach((name, value) -> result.put(name, value.snapshot()));
            return result;
        }
    }

    static final class Observer implements BeanPostProcessor {
        @Override public Object postProcessAfterInitialization(Object bean, String name) {
            if (bean instanceof RedisClient client) client.addListener(new CommandListener() {
                @Override public void commandStarted(CommandStartedEvent event) {
                    // Only inspect the fixed read-only Facts command; never retain arguments or payloads.
                    if (event.getCommand().getType().toString().equals("EVAL_RO")
                            && event.getCommand().getArgs().toCommandString().contains(":matching:worker:platform-properties:app-a-sim"))
                        MEASUREMENTS.factsRead();
                }
            });
            if (!(bean instanceof WorkerMatchingCatalog)) return bean;
            return Proxy.newProxyInstance(WorkerMatchingCatalog.class.getClassLoader(), new Class<?>[]{WorkerMatchingCatalog.class},
                    (proxy, method, args) -> {
                        String phase = method.getName();
                        boolean measured = (phase.equals("refill") || phase.equals("take"))
                                && args != null && args.length > 0 && "app-a-sim".equals(args[0]);
                        long started = measured ? MEASUREMENTS.begin() : 0;
                        boolean failed = true;
                        if (started != 0) MEASUREMENTS.phase.set(phase);
                        try {
                            var result = method.invoke(bean, args);
                            failed = false;
                            return result;
                        } catch (InvocationTargetException error) { throw error.getCause(); }
                        finally {
                            if (started != 0) {
                                MEASUREMENTS.complete(phase, started, failed);
                                MEASUREMENTS.phase.remove();
                            }
                        }
                    });
        }
    }
}
