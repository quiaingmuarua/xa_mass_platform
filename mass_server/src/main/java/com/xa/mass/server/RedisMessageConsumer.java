//package com.xa.mass.server;
//
//import io.netty.channel.Channel;
//import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.scheduling.annotation.Scheduled;
//import org.springframework.scheduling.config.Task;
//import org.springframework.stereotype.Component;
//
//@Component
//public class RedisMessageConsumer {
//
//    @Autowired
//    private StringRedisTemplate redisTemplate;
//
//    @Scheduled(fixedDelay = 100)
//    public void pollTasks() {
//        String taskJson = redisTemplate.opsForList().leftPop("taskQueue");
//        if (taskJson != null) {
//            Task task = parseTask(taskJson);
//            Channel channel = ClientSessionManager.getChannel(task.getClientId());
//            if (channel != null && channel.isActive()) {
//                channel.writeAndFlush(new TextWebSocketFrame(taskJson));
//                TaskResultHandler.startTaskTimeoutCheck(task.getTaskId(), channel);
//            }
//        }
//    }
//}