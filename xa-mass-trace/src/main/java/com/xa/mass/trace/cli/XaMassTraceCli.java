package com.xa.mass.trace.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xa.mass.trace.operator.TraceAnalyzeRequest;
import com.xa.mass.trace.operator.TraceAssignmentRequest;
import com.xa.mass.trace.operator.TraceOperatorService;
import com.xa.mass.trace.operator.TraceStatsRequest;
import com.xa.mass.trace.operator.TraceTimelineRequest;
import com.xa.mass.trace.operator.TraceValidateRequest;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

import java.io.PrintStream;
import java.util.concurrent.Callable;

public final class XaMassTraceCli {

    private static final int EXIT_OK = 0;
    private static final int EXIT_USAGE = 2;
    private static final int EXIT_VALIDATION_FAILED = 3;
    private static final int EXIT_SCENARIO_FAILED = 4;

    private final TraceOperatorService operatorService;
    private final ObjectMapper objectMapper;

    public XaMassTraceCli() {
        this(new TraceOperatorService());
    }

    XaMassTraceCli(TraceOperatorService operatorService) {
        this.operatorService = operatorService;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public static void main(String[] args) throws Exception {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public static int run(String[] args, PrintStream out, PrintStream err) throws Exception {
        return new XaMassTraceCli().execute(args, out, err);
    }

    int execute(String[] args, PrintStream out, PrintStream err) {
        RootCommand root = new RootCommand(operatorService, objectMapper, out);
        CommandLine commandLine = new CommandLine(root);
        commandLine.addSubcommand("timeline", new TimelineCommand());
        commandLine.addSubcommand("stats", new StatsCommand());
        commandLine.addSubcommand("assignment", new AssignmentCommand());
        commandLine.addSubcommand("validate", new ValidateCommand());
        commandLine.addSubcommand("analyze", new AnalyzeCommand());
        commandLine.setExecutionExceptionHandler((ex, cmd, parseResult) -> {
            err.println(ex.getMessage());
            return EXIT_USAGE;
        });
        commandLine.setParameterExceptionHandler((ex, args1) -> handleParameterException(ex, err));
        return commandLine.execute(args == null ? new String[0] : args);
    }

    private static int handleParameterException(ParameterException ex, PrintStream err) {
        err.println(ex.getMessage());
        ex.getCommandLine().usage(err);
        return EXIT_USAGE;
    }

    @Command(
            name = "xa-mass-trace",
            mixinStandardHelpOptions = true,
            description = "Trace operator CLI for XA Mass canonical trace artifacts."
    )
    static final class RootCommand implements Runnable {

        @Spec
        private CommandLine.Model.CommandSpec spec;

        private final TraceOperatorService operatorService;
        private final ObjectMapper objectMapper;
        private final PrintStream out;

        RootCommand(TraceOperatorService operatorService,
                    ObjectMapper objectMapper,
                    PrintStream out) {
            this.operatorService = operatorService;
            this.objectMapper = objectMapper;
            this.out = out;
        }

        @Override
        public void run() {
            spec.commandLine().usage(out);
        }

        int printJson(Object payload) throws Exception {
            out.println(objectMapper.writeValueAsString(payload));
            return EXIT_OK;
        }
    }

    @Command(name = "timeline", description = "Read ordered task or task-work trace timeline.")
    static final class TimelineCommand implements Callable<Integer> {

        @ParentCommand
        private RootCommand root;

        @Option(names = "--path", required = true, description = "Trace file or directory path.")
        private String path;

        @Option(names = "--task-id", required = true, description = "Task id.")
        private String taskId;

        @Option(names = "--message-id", description = "Optional message id filter.")
        private String messageId;

        @Option(names = "--limit", description = "Maximum rows to return.")
        private Integer limit;

        @Option(names = "--json", description = "Emit JSON output.")
        private boolean json;

        @Override
        public Integer call() throws Exception {
            var response = root.operatorService.timeline(new TraceTimelineRequest(path, taskId, messageId, limit));
            if (json) {
                return root.printJson(response);
            }
            root.out.printf("timeline source=%s taskId=%s messageId=%s count=%d%n",
                    response.source(),
                    response.taskId(),
                    response.messageId(),
                    response.count());
            for (var row : response.events()) {
                root.out.printf("%s %-32s task=%s msg=%s attempt=%s %s->%s reason=%s source=%s%n",
                        row.tsIso(),
                        row.eventType(),
                        row.taskId(),
                        nullToDash(row.messageId()),
                        nullToDash(row.attemptId()),
                        nullToDash(row.src()),
                        nullToDash(row.dst()),
                        nullToDash(row.reason()),
                        nullToDash(row.source()));
            }
            return EXIT_OK;
        }
    }

    @Command(name = "stats", description = "Read grouped trace event counts.")
    static final class StatsCommand implements Callable<Integer> {

        @ParentCommand
        private RootCommand root;

        @Option(names = "--path", required = true, description = "Trace file or directory path.")
        private String path;

        @Option(names = "--task-id", description = "Optional task id filter.")
        private String taskId;

        @Option(names = "--event-type", description = "Optional event type filter.")
        private String eventType;

        @Option(names = "--severity", description = "Optional severity filter.")
        private String severity;

        @Option(names = "--limit", description = "Maximum grouped rows to return.")
        private Integer limit;

        @Option(names = "--json", description = "Emit JSON output.")
        private boolean json;

        @Override
        public Integer call() throws Exception {
            var response = root.operatorService.stats(
                    new TraceStatsRequest(path, taskId, eventType, severity, limit));
            if (json) {
                return root.printJson(response);
            }
            root.out.printf("stats source=%s taskId=%s eventType=%s severity=%s count=%d%n",
                    response.source(),
                    response.taskId(),
                    response.eventType(),
                    response.severity(),
                    response.count());
            for (var row : response.rows()) {
                root.out.printf("%-32s %-8s %d%n", row.eventType(), row.severity(), row.count());
            }
            return EXIT_OK;
        }
    }

    @Command(name = "assignment", description = "Read schedule and assignment trace decisions.")
    static final class AssignmentCommand implements Callable<Integer> {

        @ParentCommand
        private RootCommand root;

        @Option(names = "--path", required = true, description = "Trace file or directory path.")
        private String path;

        @Option(names = "--task-id", required = true, description = "Task id.")
        private String taskId;

        @Option(names = "--limit", description = "Maximum rows to return.")
        private Integer limit;

        @Option(names = "--json", description = "Emit JSON output.")
        private boolean json;

        @Override
        public Integer call() throws Exception {
            var response = root.operatorService.assignment(new TraceAssignmentRequest(path, taskId, limit));
            if (json) {
                return root.printJson(response);
            }
            root.out.printf("assignment source=%s taskId=%s count=%d%n",
                    response.source(),
                    response.taskId(),
                    response.count());
            for (var row : response.events()) {
                root.out.printf("%s %-34s result=%s lane=%s priority=%s foreground=%s worker=%s ctx=%s reason=%s counts=%s%n",
                        row.tsIso(),
                        row.eventType(),
                        nullToDash(row.result()),
                        nullToDash(row.dispatchLane()),
                        nullToDash(row.dispatchPriority()),
                        row.foreground() == null ? "-" : row.foreground(),
                        nullToDash(row.workerId()),
                        nullToDash(row.workerContextId()),
                        nullToDash(row.reason()),
                        assignmentCounts(row));
            }
            return EXIT_OK;
        }

        private String assignmentCounts(com.xa.mass.trace.query.TraceAssignmentRow row) {
            StringBuilder builder = new StringBuilder();
            appendCount(builder, "matched", row.matchedWorkerCount());
            appendCount(builder, "candidates", row.dispatchCandidateCount());
            appendCount(builder, "dispatched", row.dispatchedMessageCount());
            appendCount(builder, "used", row.usedWorkerCount());
            appendCount(builder, "budget", row.workerBudget());
            appendCount(builder, "taskWorkers", row.currentTaskWorkerCount());
            appendBoolean(builder, "budgetLimited", row.budgetLimited());
            appendCount(builder, "pending", row.pendingMessageCount());
            appendCount(builder, "slots", row.dispatchSlotCount());
            appendCount(builder, "perWorker", row.perWorkerBatchLimit());
            appendCount(builder, "queue", row.queueDepth());
            appendCount(builder, "rank", row.candidateRank());
            appendCount(builder, "active", row.workerActiveLeaseCount());
            appendDouble(builder, "load", row.workerEstimatedLoadRatio());
            return builder.isEmpty() ? "-" : builder.toString();
        }

        private void appendCount(StringBuilder builder, String name, Integer value) {
            if (value == null) {
                return;
            }
            if (!builder.isEmpty()) {
                builder.append(',');
            }
            builder.append(name).append('=').append(value);
        }

        private void appendBoolean(StringBuilder builder, String name, Boolean value) {
            if (value == null) {
                return;
            }
            if (!builder.isEmpty()) {
                builder.append(',');
            }
            builder.append(name).append('=').append(value);
        }

        private void appendDouble(StringBuilder builder, String name, Double value) {
            if (value == null) {
                return;
            }
            if (!builder.isEmpty()) {
                builder.append(',');
            }
            builder.append(name).append('=').append(value);
        }
    }

    @Command(name = "validate", description = "Validate canonical trace JSONL artifacts.")
    static final class ValidateCommand implements Callable<Integer> {

        @ParentCommand
        private RootCommand root;

        @Option(names = "--path", required = true, description = "Trace file or directory path.")
        private String path;

        @Option(names = "--json", description = "Emit JSON output.")
        private boolean json;

        @Override
        public Integer call() throws Exception {
            var response = root.operatorService.validate(new TraceValidateRequest(path));
            if (json) {
                root.out.println(root.objectMapper.writeValueAsString(response));
            } else {
                root.out.printf("validate source=%s files=%d validRows=%d issues=%d%n",
                        response.source().inputPath(),
                        response.source().fileCount(),
                        response.validRows(),
                        response.issues().size());
                for (var issue : response.issues()) {
                    root.out.printf("[%s] %s:%d %s%n", issue.code(), issue.file(), issue.line(), issue.message());
                }
            }
            return response.valid() ? EXIT_OK : EXIT_VALIDATION_FAILED;
        }
    }

    @Command(name = "analyze", description = "Run scenario-oriented trace analysis.")
    static final class AnalyzeCommand implements Callable<Integer> {

        @ParentCommand
        private RootCommand root;

        @Option(names = "--path", required = true, description = "Trace file or directory path.")
        private String path;

        @Option(names = "--scenario", required = true, description = "Scenario id.")
        private String scenarioId;

        @Option(names = "--task-id", required = true, description = "Task id.")
        private String taskId;

        @Option(names = "--json", description = "Emit JSON output.")
        private boolean json;

        @Override
        public Integer call() throws Exception {
            var response = root.operatorService.analyze(new TraceAnalyzeRequest(path, scenarioId, taskId));
            if (json) {
                root.out.println(root.objectMapper.writeValueAsString(response));
            } else {
                root.out.printf("analyze source=%s scenario=%s taskId=%s ok=%s events=%d issues=%d%n",
                        response.source(),
                        response.scenarioId(),
                        response.taskId(),
                        response.ok(),
                        response.eventCount(),
                        response.issues().size());
                for (var issue : response.issues()) {
                    root.out.printf("[%s] %s%n", issue.code(), issue.message());
                }
            }
            return response.ok() ? EXIT_OK : EXIT_SCENARIO_FAILED;
        }
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
