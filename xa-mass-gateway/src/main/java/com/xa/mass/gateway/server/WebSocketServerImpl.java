package com.xa.mass.gateway.server;

import com.xa.mass.gateway.dispatcher.DispatcherContext;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.server.DispatcherInboundHandler;
import com.xa.mass.gateway.session.ServerSessionManager;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket 服务器的实现类。
 * 负责启动、停止 Netty WebSocket 服务器，并管理连接。
 */
public class WebSocketServerImpl implements MassWebSocketServer {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketServerImpl.class);


    private int port;


    private String websocketPath;

    // Netty 的 Boss EventLoopGroup，用于接受客户端连接
    private EventLoopGroup bossGroup;
    // Netty 的 Worker EventLoopGroup，用于处理已接受连接的 I/O 操作
    private EventLoopGroup workerGroup;

    // 服务器运行状态标志
    private volatile boolean running = false;

    // 原子计数器，用于统计当前活跃的连接数
    private final AtomicLong activeConnections = new AtomicLong(0);

    // 注入服务器会话管理器

    private ServerSessionManager sessionManager;

    private DispatchRuntimeContext dispatcherContext;

    /**
     * 启动 WebSocket 服务器。
     * @param port 要监听的端口号。
     */
    @Override
    public void start(int port) {
        this.port = port; // 更新当前实例的端口号
        this.running = true; // 设置运行状态为 true

        // 检查 Epoll 是否可用，以决定使用 Nio 还是 Epoll 模型
        boolean useEpoll = Epoll.isAvailable();
        logger.info("Netty Epoll available: {}", useEpoll);

        // 根据是否使用 Epoll 初始化 Boss 和 Worker EventLoopGroup
        // Boss Group 通常只需要一个线程来接受连接
        bossGroup = useEpoll
                ? new EpollEventLoopGroup(1, new DefaultThreadFactory("boss")) // Epoll Boss Group
                : new NioEventLoopGroup(1, new DefaultThreadFactory("boss"));  // Nio Boss Group
        // Worker Group 使用 Netty 默认的线程数（通常是 CPU 核数 * 2）
        workerGroup = useEpoll
                ? new EpollEventLoopGroup(0, new DefaultThreadFactory("worker")) // Epoll Worker Group
                : new NioEventLoopGroup(0, new DefaultThreadFactory("worker")); // Nio Worker Group

        try {
            ServerBootstrap b = new ServerBootstrap(); // 创建 Netty 服务器启动引导类
            b.group(bossGroup, workerGroup) // 设置 Boss 和 Worker Group
                    .channel(useEpoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class) // 设置服务器 Channel 类型
                    .option(ChannelOption.SO_BACKLOG, 1024) // 设置 TCP 连接请求的最大队列长度
                    .childOption(ChannelOption.SO_KEEPALIVE, true) // 为子连接（已接受的连接）开启 TCP KeepAlive
                    .childOption(ChannelOption.TCP_NODELAY, true)  // 禁用 Nagle 算法，减少延迟
                    .childOption(ChannelOption.SO_REUSEADDR, true) // 允许端口重用
                    .childOption(ChannelOption.SO_RCVBUF, 512 * 1024) // 设置接收缓冲区大小
                    .childOption(ChannelOption.SO_SNDBUF, 512 * 1024); // 设置发送缓冲区大小

            if (useEpoll) {
                // 如果使用 Epoll，可以开启 SO_REUSEPORT 允许多个进程绑定到同一端口（需要内核支持）
                b.option(EpollChannelOption.SO_REUSEPORT, true);
            }

            // 设置子连接的 ChannelPipeline 初始化器
            b.childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ChannelPipeline pipeline = ch.pipeline();
                    // HTTP 编解码器，用于处理 HTTP 请求（WebSocket 握手基于 HTTP）
                    pipeline.addLast(new HttpServerCodec());
                    // 将 HTTP 消息的多个部分聚合为单个 FullHttpRequest 或 FullHttpResponse
                    pipeline.addLast(new HttpObjectAggregator(65536)); // 最大聚合内容长度 64KB
                    // WebSocket 协议处理器，处理握手、帧类型等
                    pipeline.addLast(new WebSocketServerProtocolHandler(
                            websocketPath, // WebSocket 路径
                            null,          // subprotocols，这里不指定
                            true,          // allowExtensions
                            65536 * 10,    // maxFrameSize，最大帧大小 640KB
                            false,         // allowMaskMismatch
                            true,          // checkStartsWith
                            10000L         // handshakeTimeoutMillis，握手超时时间 10 秒
                    ));
                    // 自定义连接统计处理器
                    pipeline.addLast(new ConnectionStatsHandler());
                    // 注入 DispatcherInboundHandler
                    pipeline.addLast(new DispatcherInboundHandler(dispatcherContext));
                }
            });

            // 绑定端口并同步等待成功
            ChannelFuture future = b.bind(port).sync();
            if (future.isSuccess()) {
                logger.info("✅ WebSocket server started on port {} with path {}", port, websocketPath);
            } else {
                logger.error("❌ Failed to start WebSocket server on port {}", port, future.cause());
                stop(); // 启动失败则尝试停止服务器并释放资源
            }
        } catch (InterruptedException e) {
            logger.error("Server startup interrupted", e);
            Thread.currentThread().interrupt(); // 恢复中断状态
            stop(); // 发生中断时也尝试停止服务器
        } catch (Exception e) {
            logger.error("Unexpected error during server startup", e);
            stop(); // 发生其他异常时也尝试停止服务器
        }
    }

    /**
     * 内部类，用于统计和记录连接的建立与关闭。
     */
    private class ConnectionStatsHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            long count = activeConnections.incrementAndGet(); // 原子增加活跃连接数
            logger.debug("Connection opened: {}, total={}", ctx.channel().remoteAddress(), count);
            super.channelActive(ctx); // 调用父类方法，确保事件继续传播
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            long count = activeConnections.decrementAndGet(); // 原子减少活跃连接数
            logger.debug("Connection closed: {}, total={}", ctx.channel().remoteAddress(), count);
            super.channelInactive(ctx); // 调用父类方法
        }
    }

    /**
     * Spring Bean 销毁前自动调用此方法停止服务器。
     * 优雅地关闭 Netty 的 EventLoopGroup。
     */
    @PreDestroy
    @Override
    public void stop() {
        running = false; // 设置运行状态为 false
        logger.info("🔻 Shutting down WebSocket server...");
        // 优雅关闭 Boss Group
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        // 优雅关闭 Worker Group
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        logger.info("WebSocket server shutdown complete.");
    }

    /**
     * 检查服务器是否正在运行。
     * @return 如果服务器正在运行则返回 true，否则返回 false。
     */
    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 根据客户端 ID 获取对应的 Channel。
     * 注意：此实现中硬编码了连接角色 "messaegs_task"。
     * @param clientId 客户端标识符。
     * @return 客户端的 Channel，如果未找到则返回 null。
     */
    @Override
    public Channel getClientChannel(String clientId) {
        // 通过会话管理器获取指定客户端 ID 和固定角色 "messaegs_task" 的 Channel
        return sessionManager.getChannel(clientId, "messaegs_task");
    }

    /**
     * 获取当前活跃的连接数。
     * @return 当前活跃连接的数量。
     */
    public long getActiveConnectionCount() {
        return activeConnections.get();
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setWebsocketPath(String websocketPath) {
        this.websocketPath = websocketPath;
    }
    public void setDispatcherContext(DispatchRuntimeContext dispatcherContext) {
        this.dispatcherContext = dispatcherContext;
    }

    public void setSessionManager(ServerSessionManager sessionManager){
        this.sessionManager=sessionManager;
    }




}