package com.xa.mass.gateway.dispatcher;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.queue.Envelope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServerMessageDispatcherShutdownTest {

    @Test
    void stopInterruptsBlockedDispatcherLoopsWithoutWaitingForPollTimeout() throws Exception {
        BlockingTransporter transporter = new BlockingTransporter();
        DispatchRuntimeContext context = mock(DispatchRuntimeContext.class);
        when(context.getMessageTransporter()).thenReturn(transporter);

        ServerMessageDispatcher dispatcher = new ServerMessageDispatcher(context);
        dispatcher.start();

        assertTrue(transporter.awaitInputLoopStart(), "expected at least one input loop to start");
        assertTrue(transporter.awaitOutputLoopStart(), "expected at least one output loop to start");

        assertTimeoutPreemptively(
                Duration.ofSeconds(3),
                dispatcher::stop,
                "dispatcher stop should interrupt blocked loops instead of waiting for the poll timeout"
        );
    }

    private static final class BlockingTransporter implements MessageTransporter<Envelope> {
        private final CountDownLatch inputStarted = new CountDownLatch(1);
        private final CountDownLatch outputStarted = new CountDownLatch(1);
        private final CountDownLatch neverRelease = new CountDownLatch(1);

        @Override
        public void sendInput(Envelope message) {
        }

        @Override
        public Envelope receiveInput(long timeout, TimeUnit unit) throws InterruptedException {
            inputStarted.countDown();
            neverRelease.await();
            return null;
        }

        @Override
        public void sendOutput(Envelope message) {
        }

        @Override
        public Envelope receiveOutput(long timeout, TimeUnit unit) throws InterruptedException {
            outputStarted.countDown();
            neverRelease.await();
            return null;
        }

        @Override
        public int inputQueueSize() {
            return 0;
        }

        @Override
        public int outputQueueSize() {
            return 0;
        }

        private boolean awaitInputLoopStart() throws InterruptedException {
            return inputStarted.await(2, TimeUnit.SECONDS);
        }

        private boolean awaitOutputLoopStart() throws InterruptedException {
            return outputStarted.await(2, TimeUnit.SECONDS);
        }
    }
}
