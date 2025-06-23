package com.xa.mass.gateway.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * 基于外部 API 的消息传输器实现示例
 * 展示如何从外部 API 获取消息而不是使用内部队列
 * 这为后续升级为多级队列或外部消息系统提供了示例
 */
public class ApiBasedMessageTransporter implements MessageTransporter {
    
    private static final Logger logger = LoggerFactory.getLogger(ApiBasedMessageTransporter.class);
    
    // 外部 API 配置
    private final String inputApiUrl;
    private final String outputApiUrl;
    private final String apiKey;
    
    public ApiBasedMessageTransporter(String inputApiUrl, String outputApiUrl, String apiKey) {
        this.inputApiUrl = inputApiUrl;
        this.outputApiUrl = outputApiUrl;
        this.apiKey = apiKey;
    }
    
    @Override
    public void sendInput(Envelope envelope) {
        // 通过外部 API 发送输入消息
        logger.info("通过外部 API 发送输入消息: {}", envelope);
        // TODO: 实现 HTTP 请求到外部 API
        // 例如：httpClient.post(inputApiUrl, envelope.toJson(), headers)
    }
    
    @Override
    public Envelope receiveInput(long timeout, TimeUnit unit) throws InterruptedException {
        // 从外部 API 轮询获取输入消息
        logger.debug("从外部 API 轮询输入消息，超时: {} {}", timeout, unit);
        
        long endTime = System.currentTimeMillis() + unit.toMillis(timeout);
        while (System.currentTimeMillis() < endTime) {
            try {
                // TODO: 实现 HTTP 请求到外部 API 获取消息
                // Envelope envelope = httpClient.get(inputApiUrl, headers);
                // if (envelope != null) {
                //     return envelope;
                // }
                
                // 模拟轮询间隔
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
        return null; // 超时返回 null
    }
    
    @Override
    public void sendOutput(Envelope envelope) {
        // 通过外部 API 发送输出消息
        logger.info("通过外部 API 发送输出消息: {}", envelope);
        // TODO: 实现 HTTP 请求到外部 API
        // 例如：httpClient.post(outputApiUrl, envelope.toJson(), headers)
    }
    
    @Override
    public Envelope receiveOutput(long timeout, TimeUnit unit) throws InterruptedException {
        // 从外部 API 轮询获取输出消息
        logger.debug("从外部 API 轮询输出消息，超时: {} {}", timeout, unit);
        
        long endTime = System.currentTimeMillis() + unit.toMillis(timeout);
        while (System.currentTimeMillis() < endTime) {
            try {
                // TODO: 实现 HTTP 请求到外部 API 获取消息
                // Envelope envelope = httpClient.get(outputApiUrl, headers);
                // if (envelope != null) {
                //     return envelope;
                // }
                
                // 模拟轮询间隔
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
        return null; // 超时返回 null
    }
    
    @Override
    public int inputQueueSize() {
        // 从外部 API 获取输入队列大小
        logger.debug("从外部 API 获取输入队列大小");
        // TODO: 实现 HTTP 请求到外部 API 获取队列大小
        // return httpClient.get(inputApiUrl + "/size", headers);
        return -1; // 外部 API 可能不支持此功能
    }
    
    @Override
    public int outputQueueSize() {
        // 从外部 API 获取输出队列大小
        logger.debug("从外部 API 获取输出队列大小");
        // TODO: 实现 HTTP 请求到外部 API 获取队列大小
        // return httpClient.get(outputApiUrl + "/size", headers);
        return -1; // 外部 API 可能不支持此功能
    }
} 