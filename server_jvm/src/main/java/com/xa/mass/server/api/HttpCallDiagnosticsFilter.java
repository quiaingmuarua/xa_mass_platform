package com.xa.mass.server.api;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Optional servlet observations. Does not retain a URL, identity or body. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(name = "xa.mass.diagnostics.enabled", havingValue = "true")
public final class HttpCallDiagnosticsFilter extends OncePerRequestFilter {
    private static final EventType INITIAL = EventType.getEventType(InitialRequest.class);
    private static final EventType COMPLETION = EventType.getEventType(Completion.class);

    @Name("xa.mass.HttpInitial") @Category("XA Mass") @Enabled(false) @StackTrace(false)
    public static final class InitialRequest extends Event {
        public String operation;
        public boolean virtualThread;
        public boolean failed;
        @Timespan(Timespan.NANOSECONDS) public long elapsedNanos;
    }

    @Name("xa.mass.HttpCompletion") @Category("XA Mass") @Enabled(false) @StackTrace(false)
    public static final class Completion extends Event {
        public String operation;
        public String reason;
        public int httpStatus;
        public boolean initialVirtualThread;
        public boolean virtualThread;
        @Timespan(Timespan.NANOSECONDS) public long elapsedNanos;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws IOException, ServletException {
        String operation = operation(request.getRequestURI());
        if (operation == null || (!INITIAL.isEnabled() && !COMPLETION.isEnabled())) {
            chain.doFilter(request, response);
            return;
        }
        var observation = new Observation(operation, response);
        var wrapped = new HttpServletRequestWrapper(request) {
            @Override public AsyncContext startAsync() {
                return observation.listen(super.startAsync());
            }
            @Override public AsyncContext startAsync(ServletRequest supplied, ServletResponse target) {
                return observation.listen(super.startAsync(supplied, target));
            }
        };
        boolean failed = true;
        try {
            chain.doFilter(wrapped, response);
            failed = false;
        } finally {
            observation.initial(failed);
            if (!observation.asyncStarted) observation.complete(failed ? "handler_error" : "synchronous", failed ? 0 : response.getStatus());
        }
    }

    static String operation(String path) {
        if (path.startsWith("/api/v1/tasks/") && path.endsWith("/items:call")) return "ITEMS_CALL";
        if (!path.startsWith("/api/v1/worker-delivery/endpoint-managers/")) return null;
        if (path.endsWith("/direct-calls")) return "DIRECT_CALL";
        if (path.endsWith("/commands:consume")) return "COMMAND_CONSUME";
        if (path.endsWith("/results:append")) return "REPORT_APPEND";
        return null;
    }

    static final class Observation implements AsyncListener {
        private final String operation;
        private final HttpServletResponse response;
        private final long started = System.nanoTime();
        private final boolean virtualThread = Thread.currentThread().isVirtual();
        private final AtomicBoolean completed = new AtomicBoolean();
        private volatile String reason = "asynchronous";
        private boolean asyncStarted;

        Observation(String operation, HttpServletResponse response) {
            this.operation = operation;
            this.response = response;
        }

        AsyncContext listen(AsyncContext context) {
            asyncStarted = true;
            try { context.addListener(this); }
            catch (RuntimeException ignored) { /* Missing completion remains missing diagnostic evidence. */ }
            return context;
        }

        void initial(boolean failed) {
            try {
                if (!INITIAL.isEnabled()) return;
                var event = new InitialRequest();
                event.operation = operation;
                event.virtualThread = virtualThread;
                event.failed = failed;
                event.elapsedNanos = System.nanoTime() - started;
                event.commit();
            } catch (RuntimeException ignored) { /* Never change HTTP completion. */ }
        }

        void complete(String reason, int status) {
            if (!completed.compareAndSet(false, true)) return;
            try {
                if (!COMPLETION.isEnabled()) return;
                var event = new Completion();
                event.operation = operation;
                event.reason = reason;
                event.httpStatus = status;
                event.initialVirtualThread = virtualThread;
                event.virtualThread = Thread.currentThread().isVirtual();
                event.elapsedNanos = System.nanoTime() - started;
                event.commit();
            } catch (RuntimeException ignored) { /* Never change HTTP completion. */ }
        }

        @Override public void onComplete(AsyncEvent event) { complete(reason, response.getStatus()); }
        @Override public void onTimeout(AsyncEvent event) { reason = "timeout"; }
        @Override public void onError(AsyncEvent event) { reason = "error"; }
        @Override public void onStartAsync(AsyncEvent event) { listen(event.getAsyncContext()); }
    }
}
