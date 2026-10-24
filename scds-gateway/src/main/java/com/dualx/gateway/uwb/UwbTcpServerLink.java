package com.dualx.gateway.uwb;

import com.dualx.core.domain.LinkConfig;
import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.LinkState;
import com.dualx.core.domain.LinkStatus;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.port.HardwareEventSink;
import com.dualx.gateway.link.LinkRegistry;
import com.dualx.gateway.link.LinkSlot;
import com.dualx.protocol.ProtocolConstants;
import com.dualx.protocol.uwb.UwbFrame;
import com.dualx.protocol.uwb.UwbFramer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * UWB 走 TCP 的接入链路（协议 B，{@link LinkKind#UWB_TCP_SERVER}）：Netty 监听，基站主动连上来。
 *
 * <p><b>端口是硬约束</b>：生产一律 {@link ProtocolConstants#UWB_TCP_SERVER_PORT}（12001）。
 * 请求里给别的端口直接 400 {@code VALIDATION_FAILED}，不接受「换个端口也能跑」——
 * 现场排障时端口与文档不一致的代价远高于多写一行校验。</p>
 *
 * <p><b>单连接汇聚</b>：新连接接入时把旧连接踢掉，{@code fdCount} 始终反映当前接入数。
 * 定位基站按约定只有一个数据源，同时两条连接只会让坐标来源变得不可判定。</p>
 *
 * <p><b>哑管道</b>：只把字节切成合法的 41B {@link UwbFrame} 并回调
 * {@link HardwareEventSink#onUwbFrame}；几何解算在 core 侧，网关不做定位计算。</p>
 *
 * <p><b>已知缺口</b>：{@code droppedTotal} / {@code crcErrorTotal} 恒为 0。
 * {@link UwbFramer} 把坏帧重同步收敛在内部，只对外给合法帧，不回报「哪个位置被丢弃、
 * 是校验不过还是纯噪声」；要准确区分必须改协议库的返回形状（冻结区，有黄金样本回归）。
 * 故此处不臆造数字——宁可让现场看到 0 并知道原因，也不把字节数冒充坏帧数。</p>
 */
public final class UwbTcpServerLink {

    private static final Logger log = LoggerFactory.getLogger(UwbTcpServerLink.class);

    private static final LinkKind KIND = LinkKind.UWB_TCP_SERVER;

    /** 空闲告警阈值（分钟）：协议 §3.2 口径，只告警不主动断链。 */
    private static final int IDLE_WARN_MINUTES = ProtocolConstants.UWB_TCP_IDLE_WARN_MINUTES;

    /** 监听积压队列：只有一个数据源，不需要大 backlog。 */
    private static final int SO_BACKLOG = 4;

    private final LinkRegistry registry;
    private final HardwareEventSink sink;
    /** 实际监听端口；生产恒为 12001，测试可注入随机端口以免抢占硬约束端口。 */
    private final int bindPort;
    private final Object connectionLock = new Object();

    private volatile EventLoopGroup bossGroup;
    private volatile EventLoopGroup workerGroup;
    private volatile Channel serverChannel;
    private volatile Channel clientChannel;
    private volatile UwbFramer framer;

    public UwbTcpServerLink(LinkRegistry registry, HardwareEventSink sink) {
        this(registry, sink, ProtocolConstants.UWB_TCP_SERVER_PORT);
    }

    /** 供测试注入随机端口；生产请用上面的构造器走 12001 硬约束。 */
    UwbTcpServerLink(LinkRegistry registry, HardwareEventSink sink, int bindPort) {
        this.registry = registry;
        this.sink = sink;
        this.bindPort = bindPort;
    }

    public LinkStatus status() {
        return registry.slot(KIND).status();
    }

    /**
     * 启动监听。
     *
     * @throws BusinessException {@code VALIDATION_FAILED}（请求端口与硬约束不符）、
     *                           {@code DEVICE_LINK_ALREADY_OPEN}（本模式或 UWB 串口模式已开）、
     *                           {@code DEVICE_LINK_SERIAL_BUSY}（监听端口无法绑定）
     */
    public LinkStatus open(LinkConfig config) {
        String host = hostOf(config);
        int port = portOf(config);

        LinkSlot slot = registry.beginOpen(KIND, null, host + ":" + port, null);
        try {
            start(host, port, slot);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            stop();
            registry.abortOpen(KIND, "启动 UWB TCP Server 被中断");
            throw new BusinessException(ApiErrorCode.DEVICE_LINK_SERIAL_BUSY,
                    "启动 UWB TCP Server 失败：" + host + ":" + port);
        } catch (Exception failure) {
            // 兜住 Exception 而非 RuntimeException：Netty 的 ChannelFuture.sync() 会用「偷抛」的方式
            // 把 BindException 这类检查异常原样抛出，漏掉它就变成接口层的 500 而不是契约要求的 409。
            stop();
            registry.abortOpen(KIND, failure.getMessage());
            log.warn("启动 UWB TCP Server 失败：{}:{}", host, port, failure);
            throw new BusinessException(ApiErrorCode.DEVICE_LINK_SERIAL_BUSY,
                    "启动 UWB TCP Server 失败：" + host + ":" + port + "（端口不可绑定或已被占用）");
        }

        registry.finishOpen(KIND);
        sink.onLinkStateChanged(KIND, LinkState.OPEN, null);
        log.info("UWB TCP Server 已启动：{}:{}", host, port);
        return slot.status();
    }

    /** 停止监听：幂等；同时断开当前接入连接。未启动时无副作用。 */
    public LinkStatus close() {
        LinkSlot slot = registry.slot(KIND);
        boolean wasActive = slot.active();
        stop();
        registry.close(KIND);
        if (wasActive) {
            sink.onLinkStateChanged(KIND, LinkState.CLOSED, null);
            log.info("UWB TCP Server 已停止");
        }
        return slot.status();
    }

    /** 实际监听端口；绑定失败前为请求端口，{@code 0} 表示由系统分配。 */
    int boundPort() {
        Channel server = serverChannel;
        if (server != null && server.localAddress() instanceof InetSocketAddress address) {
            return address.getPort();
        }
        return bindPort;
    }

    // ==================== 生命周期 ====================

    private void start(String host, int port, LinkSlot slot) throws InterruptedException {
        framer = new UwbFramer();
        bossGroup = new NioEventLoopGroup(1, daemonFactory("uwb-boss"));
        workerGroup = new NioEventLoopGroup(0, daemonFactory("uwb-worker"));

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, SO_BACKLOG)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline()
                                .addLast(new IdleStateHandler(IDLE_WARN_MINUTES, 0, 0))
                                .addLast(new UwbFrameHandler(slot));
                    }
                });

        serverChannel = bootstrap.bind(host, port).sync().channel();
    }

    private void stop() {
        Channel client;
        synchronized (connectionLock) {
            client = clientChannel;
            clientChannel = null;
        }
        closeQuietly(client);

        Channel server = serverChannel;
        serverChannel = null;
        closeQuietly(server);

        EventLoopGroup boss = bossGroup;
        EventLoopGroup worker = workerGroup;
        bossGroup = null;
        workerGroup = null;
        shutdownQuietly(boss);
        shutdownQuietly(worker);

        framer = null;
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void shutdownQuietly(EventLoopGroup group) {
        if (group != null) {
            group.shutdownGracefully(0, 500, TimeUnit.MILLISECONDS);
        }
    }

    private static void closeQuietly(Channel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (RuntimeException ignored) {
            // 关闭失败无需上报：监听已停，句柄随后由系统回收
        }
    }

    // ==================== 连接与成帧 ====================

    /**
     * 新连接接入：踢掉旧连接后再登记。
     *
     * <p>顺序很关键——先把 {@code clientChannel} 指向新连接，再关旧的；否则旧连接的
     * {@code channelInactive} 会把刚登记的新连接误判为「当前连接已断开」而清掉 {@code fdCount}。</p>
     */
    private void onConnected(Channel channel, LinkSlot slot) {
        Channel previous;
        synchronized (connectionLock) {
            previous = clientChannel;
            clientChannel = channel;
        }
        if (previous != null && previous != channel && previous.isActive()) {
            log.info("UWB 新连接接入，踢出旧连接 {}", previous.remoteAddress());
            previous.close();
        }
        slot.updateConnections(1);
        log.info("UWB 客户端已接入：{}", channel.remoteAddress());
    }

    private void onDisconnected(Channel channel, LinkSlot slot) {
        synchronized (connectionLock) {
            if (clientChannel != channel) {
                // 已被新连接踢掉：不能再把当前连接数归零
                return;
            }
            clientChannel = null;
        }
        slot.updateConnections(0);
        log.info("UWB 客户端已断开：{}", channel.remoteAddress());
    }

    /** 字节流 → 完整帧 → sink。坏帧由 framer 内部重同步丢弃，不抛异常、不中断链路。 */
    private void dispatch(byte[] chunk, LinkSlot slot) {
        UwbFramer current = framer;
        if (current == null || chunk.length == 0) {
            return;
        }
        List<UwbFrame> frames = current.feed(chunk);
        for (UwbFrame frame : frames) {
            slot.recordFrame();
            sink.onUwbFrame(frame, KIND);
        }
    }

    private static String hostOf(LinkConfig config) {
        String host = config.uwbBindHost();
        return host == null || host.isBlank() ? ProtocolConstants.UWB_TCP_SERVER_HOST : host.trim();
    }

    private int portOf(LinkConfig config) {
        Integer requested = config.uwbBindPort();
        if (requested != null && requested != bindPort) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED,
                    "UWB TCP 监听端口固定为 " + bindPort + "，不接受 " + requested);
        }
        return bindPort;
    }

    /** Netty 入站处理器：一实例一连接，因此可以是非 {@code @Sharable} 的普通处理器。 */
    private final class UwbFrameHandler extends SimpleChannelInboundHandler<ByteBuf> {

        private final LinkSlot slot;

        private UwbFrameHandler(LinkSlot slot) {
            this.slot = slot;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            onConnected(ctx.channel(), slot);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            onDisconnected(ctx.channel(), slot);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
            byte[] chunk = new byte[msg.readableBytes()];
            msg.readBytes(chunk);
            dispatch(chunk, slot);
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
            if (event instanceof IdleStateEvent) {
                // 只告警不主动断链：UWB 正常时是持续流，但现场换基站/重启造成短暂静默
                // 比「链路被网关自己掐掉」更难排查，断链交给运维或重启。
                log.warn("UWB 连接 {} 已 {} 分钟未收到字节", ctx.channel().remoteAddress(), IDLE_WARN_MINUTES);
                return;
            }
            super.userEventTriggered(ctx, event);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("UWB 连接异常，断开 {}：{}", ctx.channel().remoteAddress(), cause.getMessage());
            ctx.close();
        }
    }
}
