package com.xa.mass.android.capabilityhttp;

import android.util.JsonReader;
import android.util.JsonToken;
import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.execution.WorkerCommandDispatcher;
import com.xa.mass.worker.execution.WorkerCommandOutcome;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import fi.iki.elonen.NanoHTTPD;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class CapabilityNanoHttpServer extends NanoHTTPD {

    private static final String JSON_MEDIA_TYPE = "application/json";
    private static final String JSON_RESPONSE_TYPE =
            "application/json; charset=utf-8";
    private static final String TASK = "TASK";
    private static final String LOCAL_FORWARD = "capability-http";
    private static final String CALL_PREFIX = "/events/";
    private static final String CALL_SUFFIX = ":call";

    private final List<String> eventCodes;
    private final Set<String> eventCodeSet;
    private final WorkerCommandDispatcher dispatcher;

    CapabilityNanoHttpServer(
            String hostname,
            int port,
            Collection<? extends WorkerEventDefinition<?>> definitions
    ) {
        super(hostname, port);
        List<WorkerEventDefinition<?>> copied = copyDefinitions(definitions);
        List<String> codes = new ArrayList<>(copied.size());
        for (WorkerEventDefinition<?> definition : copied) {
            codes.add(definition.eventCode());
        }
        eventCodes = Collections.unmodifiableList(codes);
        eventCodeSet = Collections.unmodifiableSet(
                new LinkedHashSet<>(codes)
        );
        dispatcher = WorkerCommandDispatcher.forWorker(copied);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if ("/health".equals(uri)) {
            Response methodFailure = requireMethod(session, Method.GET);
            return methodFailure == null ? health() : methodFailure;
        }
        if ("/events".equals(uri)) {
            Response methodFailure = requireMethod(session, Method.GET);
            return methodFailure == null ? events() : methodFailure;
        }
        if (uri != null
                && uri.startsWith(CALL_PREFIX)
                && uri.endsWith(CALL_SUFFIX)) {
            Response methodFailure = requireMethod(session, Method.POST);
            if (methodFailure != null) {
                return methodFailure;
            }
            return call(
                    uri.substring(
                            CALL_PREFIX.length(),
                            uri.length() - CALL_SUFFIX.length()
                    ),
                    session
            );
        }
        return protocolFailure(
                Response.Status.NOT_FOUND,
                "Route was not found"
        );
    }

    private Response health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        return json(Response.Status.OK, body);
    }

    private Response events() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("events", eventCodes);
        return json(Response.Status.OK, body);
    }

    private Response call(String eventCode, IHTTPSession session) {
        if (!eventCodeSet.contains(eventCode)) {
            return workerFailure(
                    Response.Status.NOT_FOUND,
                    eventCode,
                    WorkerErrorCode.EVENT_NOT_FOUND
            );
        }
        if (!isJsonContentType(session.getHeaders())) {
            return protocolFailure(
                    Response.Status.UNSUPPORTED_MEDIA_TYPE,
                    "Content-Type must be application/json"
            );
        }

        String payload;
        try {
            Map<String, String> body = new LinkedHashMap<>();
            session.parseBody(body);
            payload = body.get("postData");
            Jsons.parseObject(payload);
        } catch (Exception error) {
            return workerFailure(
                    Response.Status.BAD_REQUEST,
                    eventCode,
                    WorkerErrorCode.EVENT_INPUT_INVALID
            );
        }

        DeliveryCommand command = DeliveryCommand.create(
                DeliveryEndpoint.TASK,
                DeliveryEndpoint.WORKER,
                eventCode,
                Long.MAX_VALUE,
                payload,
                LOCAL_FORWARD
        );
        Optional<WorkerCommandOutcome> executed = dispatcher.execute(command);
        if (executed.isEmpty()) {
            return workerFailure(
                    Response.Status.INTERNAL_ERROR,
                    eventCode,
                    WorkerErrorCode.EVENT_EXECUTION_FAILED
            );
        }
        WorkerCommandOutcome outcome = executed.get();
        if ("200".equals(outcome.outcomeCode())) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "succeeded");
            body.put("eventCode", eventCode);
            body.put("outcomeCode", outcome.outcomeCode());
            body.put("result", logicalJson(outcome.payload()));
            return json(Response.Status.OK, body);
        }
        return outcomeFailure(eventCode, outcome);
    }

    private Response outcomeFailure(
            String eventCode,
            WorkerCommandOutcome outcome
    ) {
        Response.Status status;
        if (Integer.toString(WorkerErrorCode.EVENT_INPUT_INVALID.code())
                .equals(outcome.outcomeCode())) {
            status = Response.Status.BAD_REQUEST;
        } else if (Integer.toString(WorkerErrorCode.EVENT_NOT_FOUND.code())
                .equals(outcome.outcomeCode())) {
            status = Response.Status.NOT_FOUND;
        } else {
            status = Response.Status.INTERNAL_ERROR;
        }
        Map<String, Object> body = failureBody(
                eventCode,
                outcome.outcomeCode(),
                outcome.payload()
        );
        return json(status, body);
    }

    private Response workerFailure(
            Response.Status status,
            String eventCode,
            WorkerErrorCode errorCode
    ) {
        return json(
                status,
                failureBody(
                        eventCode,
                        Integer.toString(errorCode.code()),
                        errorCode.defaultMessage()
                )
        );
    }

    private Response protocolFailure(
            Response.Status status,
            String message
    ) {
        return json(status, failureBody(null, null, message));
    }

    private Response requireMethod(
            IHTTPSession session,
            Method expected
    ) {
        if (session.getMethod() == expected) {
            return null;
        }
        return protocolFailure(
                Response.Status.METHOD_NOT_ALLOWED,
                "Method is not allowed"
        );
    }

    private static Response json(
            Response.Status status,
            Map<String, Object> body
    ) {
        Response response = newFixedLengthResponse(
                status,
                JSON_RESPONSE_TYPE,
                Jsons.toJson(body)
        );
        response.closeConnection(true);
        return response;
    }

    private static Map<String, Object> failureBody(
            String eventCode,
            String outcomeCode,
            String message
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "failed");
        if (eventCode != null) {
            body.put("eventCode", eventCode);
        }
        if (outcomeCode != null) {
            body.put("outcomeCode", outcomeCode);
        }
        body.put("message", message);
        return body;
    }

    private static boolean isJsonContentType(
            Map<String, String> headers
    ) {
        if (headers == null) {
            return false;
        }
        String contentType = null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if ("content-type".equalsIgnoreCase(entry.getKey())) {
                contentType = entry.getValue();
                break;
            }
        }
        if (contentType == null) {
            return false;
        }
        int parameter = contentType.indexOf(';');
        String mediaType = parameter < 0
                ? contentType
                : contentType.substring(0, parameter);
        return JSON_MEDIA_TYPE.equals(
                mediaType.trim().toLowerCase(Locale.ROOT)
        );
    }

    private static List<WorkerEventDefinition<?>> copyDefinitions(
            Collection<? extends WorkerEventDefinition<?>> definitions
    ) {
        Objects.requireNonNull(definitions, "definitions");
        List<WorkerEventDefinition<?>> copied = new ArrayList<>();
        Set<String> eventCodes = new LinkedHashSet<>();
        for (WorkerEventDefinition<?> definition : definitions) {
            WorkerEventDefinition<?> resolved = Objects.requireNonNull(
                    definition,
                    "definition"
            );
            if (!TASK.equals(resolved.src())) {
                throw new IllegalArgumentException(
                        "Capability HTTP accepts only TASK definitions"
                );
            }
            if (!eventCodes.add(resolved.eventCode())) {
                throw new IllegalArgumentException(
                        "Duplicate capability eventCode: "
                                + resolved.eventCode()
                );
            }
            copied.add(resolved);
        }
        return Collections.unmodifiableList(copied);
    }

    private static Object logicalJson(String value) {
        try (JsonReader reader = new JsonReader(new StringReader(value))) {
            reader.setLenient(false);
            Object parsed = readJsonValue(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                return value;
            }
            return parsed;
        } catch (IOException | RuntimeException ignored) {
            return value;
        }
    }

    private static Object readJsonValue(JsonReader reader)
            throws IOException {
        JsonToken token = reader.peek();
        if (token == JsonToken.BEGIN_OBJECT) {
            Map<String, Object> object = new LinkedHashMap<>();
            reader.beginObject();
            while (reader.hasNext()) {
                object.put(reader.nextName(), readJsonValue(reader));
            }
            reader.endObject();
            return object;
        }
        if (token == JsonToken.BEGIN_ARRAY) {
            List<Object> array = new ArrayList<>();
            reader.beginArray();
            while (reader.hasNext()) {
                array.add(readJsonValue(reader));
            }
            reader.endArray();
            return array;
        }
        if (token == JsonToken.STRING) {
            return reader.nextString();
        }
        if (token == JsonToken.NUMBER) {
            return number(reader.nextString());
        }
        if (token == JsonToken.BOOLEAN) {
            return reader.nextBoolean();
        }
        if (token == JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
        throw new IllegalArgumentException("Unsupported JSON token");
    }

    private static Number number(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return new BigDecimal(value);
        }
    }
}
