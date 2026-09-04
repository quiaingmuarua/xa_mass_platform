package com.xa.mass.workerdelivery.protocol;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.KERNEL;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.SYSTEM;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReportOutcomeClass;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class WorkerDeliveryProtocolTest {

    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    @Test
    void commandHasStableWireAndRoundTrips() {
        DeliveryCommand command = command();
        String encoded = codec.encodeDeliveryCommand(command);

        assertEquals(
                commandJson(),
                encoded
        );
        assertEquals(command, codec.decodeDeliveryCommand(encoded));
    }

    @Test
    void reportHasStableWireAndRoundTrips() {
        DeliveryReport report = DeliveryReport.fromCommand(
                command(),
                WORKER,
                "worker-1",
                "200",
                "{\"isValid\":true}"
        );
        String encoded = codec.encodeDeliveryReport(report);

        assertEquals(
                "{\"dst\":\"TASK\",\"forward\":\"context\","
                        + "\"messageType\":\"telecom.phone.inspect\","
                        + "\"outcomeCode\":\"200\","
                        + "\"payload\":\"{\\\"isValid\\\":true}\","
                        + "\"sourceId\":\"worker-1\",\"src\":\"WORKER\"}",
                encoded
        );
        assertEquals(report, codec.decodeDeliveryReport(encoded));
        assertEquals(
                report,
                codec.decodeDeliveryReport(
                        codec.encodeDeliveryReportFields(report)
                )
        );
    }

    @Test
    void factoriesBuildReportsWithoutDeliveryCorrelationIdentity() {
        DeliveryCommand command = DeliveryCommand.create(
                TASK,
                WORKER,
                "event",
                1,
                "{}",
                "context"
        );
        DeliveryReport report = DeliveryReport.fromCommand(
                command,
                ADAPTER,
                "adapter-1",
                "23002",
                "null"
        );
        assertEquals(ADAPTER, report.src());
        assertEquals("adapter-1", report.sourceId());
        assertEquals(command.src(), report.dst());
        assertEquals(command.messageType(), report.messageType());
        assertEquals(command.forward(), report.forward());

        DeliveryReport active = DeliveryReport.create(
                WORKER,
                "worker-1",
                ADAPTER,
                "worker.connection.identify",
                "200",
                "null",
                ""
        );
        assertEquals(WORKER, active.src());
        assertEquals("worker-1", active.sourceId());
        assertEquals(ADAPTER, active.dst());
    }

    @Test
    void connectionControlUsesCommandAndReportWire() {
        DeliveryReport identity = DeliveryReport.create(
                WORKER,
                "opaque-worker-id",
                ADAPTER,
                WorkerDeliveryProtocol.WORKER_CONNECTION_IDENTIFY_EVENT_CODE,
                "200",
                "null",
                ""
        );
        DeliveryReport decodedIdentity = codec.decodeDeliveryReport(
                codec.encodeDeliveryReport(identity)
        );
        assertEquals(identity, decodedIdentity);
        assertEquals("opaque-worker-id", decodedIdentity.sourceId());
        assertEquals("null", decodedIdentity.payload());

        DeliveryCommand close = DeliveryCommand.create(
                ADAPTER,
                WORKER,
                WorkerDeliveryProtocol.WORKER_CONNECTION_CLOSE_EVENT_CODE,
                1_234_567_890L,
                "null",
                ""
        );
        assertEquals(close, codec.decodeDeliveryCommand(
                codec.encodeDeliveryCommand(close)
        ));
    }

    @Test
    void endpointWireValuesAreExplicit() {
        assertEquals(TASK, DeliveryEndpoint.fromWire("TASK"));
        assertEquals(SYSTEM, DeliveryEndpoint.fromWire("SYSTEM"));
        assertEquals(KERNEL, DeliveryEndpoint.fromWire("KERNEL"));
        assertEquals(ADAPTER, DeliveryEndpoint.fromWire("ADAPTER"));
        assertEquals(WORKER, DeliveryEndpoint.fromWire("WORKER"));
        assertThrows(
                IllegalArgumentException.class,
                () -> DeliveryEndpoint.fromWire("task")
        );
    }

    @Test
    void codecsRejectUnknownAndLegacyFields() {
        assertNull(codec.decodeDeliveryCommand(
                commandJson().replace(
                        "\"src\":\"TASK\"}",
                        "\"src\":\"TASK\",\"extra\":true}"
                )
        ));
        assertNull(codec.decodeDeliveryCommand(
                commandJson().replace(
                        "\"src\":\"TASK\"}",
                        "\"src\":\"TASK\",\"messageId\":\"legacy\"}"
                )
        ));
        assertNull(codec.decodeDeliveryReport(
                "{\"dst\":\"TASK\",\"forward\":\"context\","
                        + "\"messageType\":\"event\","
                        + "\"outcomeCode\":\"200\",\"payload\":\"null\"}"
        ));
        String report = codec.encodeDeliveryReport(
                DeliveryReport.fromCommand(
                        command(),
                        WORKER,
                        "worker-1",
                        "200",
                        "null"
                )
        );
        assertNull(codec.decodeDeliveryReport(report.replace(
                "\"src\":\"WORKER\"}",
                "\"src\":\"WORKER\",\"extra\":true}"
        )));
        assertNull(codec.decodeDeliveryReport(report.replace(
                "\"src\":\"WORKER\"}",
                "\"src\":\"WORKER\",\"messageId\":\"legacy\"}"
        )));
        assertNull(codec.decodeDeliveryReport(report.replace(
                "\"sourceId\":\"worker-1\"",
                "\"sourceId\":1"
        )));
        assertNull(codec.decodeDeliveryReport(report.replace(
                "\"sourceId\":\"worker-1\"",
                "\"sourceId\":\" \""
        )));
    }

    @Test
    void outcomeClassificationUsesOwnerPrefixWithoutWidthValidation() {
        assertEquals(
                DeliveryReportOutcomeClass.SUCCESS,
                WorkerDeliveryProtocol.classifyDeliveryReportOutcomeCode("200")
        );
        assertEquals(
                DeliveryReportOutcomeClass.WORKER_FAILURE,
                WorkerDeliveryProtocol.classifyDeliveryReportOutcomeCode("33001")
        );
        assertEquals(
                DeliveryReportOutcomeClass.ADAPTER_REJECTION,
                WorkerDeliveryProtocol.classifyDeliveryReportOutcomeCode("23001")
        );
        assertEquals(
                DeliveryReportOutcomeClass.ADAPTER_REJECTION,
                WorkerDeliveryProtocol.classifyDeliveryReportOutcomeCode("1400")
        );
        assertNull(WorkerDeliveryProtocol.classifyDeliveryReportOutcomeCode(" "));
    }

    @Test
    void deliveryDtosDoNotExposeMessageId() {
        assertTrue(Modifier.isPrivate(
                DeliveryCommand.class.getDeclaredConstructors()[0]
                        .getModifiers()
        ));
        assertTrue(Modifier.isPrivate(
                DeliveryReport.class.getDeclaredConstructors()[0]
                        .getModifiers()
        ));
        assertTrue(Arrays.stream(DeliveryCommand.class.getDeclaredFields())
                .noneMatch(field -> field.getName().equals("messageId")));
        assertTrue(Arrays.stream(DeliveryReport.class.getDeclaredFields())
                .noneMatch(field -> field.getName().equals("messageId")));
        assertTrue(Arrays.stream(DeliveryCommand.class.getMethods())
                .noneMatch(method -> method.getName().equals("messageId")));
        assertTrue(Arrays.stream(DeliveryReport.class.getMethods())
                .noneMatch(method -> method.getName().equals("messageId")));
        assertThrows(
                IllegalArgumentException.class,
                () -> DeliveryCommand.create(
                        TASK,
                        WORKER,
                        "event",
                        1,
                        "{}",
                        ""
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> DeliveryReport.create(
                        WORKER,
                        " ",
                        TASK,
                        "event",
                        "200",
                        "null",
                        "context"
                )
        );
    }

    @Test
    void legacyProtocolTypesAndCodecMethodsAreAbsent() {
        for (String type : new String[]{
                "WorkerCommand",
                "WorkerResult",
                "WorkerMessageEndpoint",
                "WorkerResultOutcomeClass"
        }) {
            assertThrows(
                    ClassNotFoundException.class,
                    () -> Class.forName(
                            WorkerDeliveryProtocol.class.getName()
                                    + "$"
                                    + type
                    )
            );
        }
        assertTrue(Arrays.stream(WorkerDeliveryCodec.class.getMethods())
                .map(method -> method.getName())
                .noneMatch(name -> name.equals("encodeWorkerCommand")
                        || name.equals("decodeWorkerCommand")
                        || name.equals("encodeWorkerResult")
                        || name.equals("decodeWorkerResult")));
    }

    private DeliveryCommand command() {
        return codec.decodeDeliveryCommand(commandJson());
    }

    private static String commandJson() {
        return "{\"dst\":\"WORKER\",\"executeBeforeMillis\":1234567890,"
                + "\"forward\":\"context\","
                + "\"messageType\":\"telecom.phone.inspect\","
                + "\"payload\":\"{\\\"phoneNumber\\\":"
                + "\\\"+14155552671\\\"}\",\"src\":\"TASK\"}";
    }
}
