package com.xa.mass.server.manager;

import com.xa.mass.server.handler.OutboundMessage;
import com.xa.mass.server.schedule.OutboundQueue;
import com.xa.mass.server.schedule.QueueService;
import io.netty.channel.Channel;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class OutboundQueueManager {

    private static final OutboundQueueManager INSTANCE = new OutboundQueueManager();

    public static OutboundQueueManager getInstance() {
        return INSTANCE;
    }


    private final QueueService<OutboundMessage> messageQueue = OutboundQueue.getInstance();

    public OutboundQueueManager() {
        Thread worker = new Thread(this::processQueue);
        worker.setDaemon(true);
        worker.start();
    }

    public void  enqueue(OutboundMessage message) {
        try {
            messageQueue.enqueue(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }



    private void processQueue() {
        while (true) {
            try {
                OutboundMessage message = messageQueue.dequeue();
                message.send();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
