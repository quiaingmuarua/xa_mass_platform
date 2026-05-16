package com.xa.mass.sdk.event;

/**
 * Stable built-in platform control-plane event codes.
 *
 * <p>These are runtime-owned events exposed by the SDK itself. Business task
 * events should be registered explicitly by the embedding runtime instead of
 * being added here.
 */
public final class PlatformEventCodes {

    public static final String TASK_APPROVE = "platform.task.approve";
    public static final String TASK_REJECT = "platform.task.reject";
    public static final String TASK_BLOCK = "platform.task.block";
    public static final String TASK_PAUSE = "platform.task.pause";
    public static final String TASK_RESUME = "platform.task.resume";
    public static final String TASK_CANCEL = "platform.task.cancel";
    public static final String TASK_TERMINATE = "platform.task.terminate";
    public static final String TASK_APPEND_ITEMS = "platform.task.append-items";
    public static final String TASK_SEAL = "platform.task.seal";

    public static final String WORKER_REGISTER = "platform.worker.register";

    public static final String META_PROJECTS_LIST = "platform.meta.projects.list";
    public static final String META_PROJECT_GET = "platform.meta.project.get";
    public static final String META_PROJECT_EVENTS_LIST = "platform.meta.project-events.list";
    public static final String META_EVENTS_LIST = "platform.meta.events.list";
    public static final String META_EVENT_GET = "platform.meta.event.get";

    private PlatformEventCodes() {
    }
}
