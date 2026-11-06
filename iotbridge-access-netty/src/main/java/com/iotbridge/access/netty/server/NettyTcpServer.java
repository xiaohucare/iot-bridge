package com.iotbridge.access.netty.server;

import com.iotbridge.access.netty.api.AccessServer;
import com.iotbridge.access.netty.api.UplinkFrameListener;
import com.iotbridge.access.netty.config.NettyProperties;
import com.iotbridge.access.netty.config.NettyProperties.FrameMode;
import com.iotbridge.access.netty.config.NettyProperties.Listener;
import com.iotbridge.access.netty.handler.DeviceInboundHandler;
import com.iotbridge.access.netty.session.ChannelManager;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Netty TCP 长连接服务端。
 *
 * <p>共享一组 boss/worker 线程组，按 {@code iotbridge.netty.listeners} 配置绑定多个监听端口，
 * 每个端口绑定一种上行协议及其切帧方式（DELIMITER 文本 / LENGTH_FIELD 二进制），从而实现
 * 多协议同进程并行接入。未配置 listeners 时，用扁平的全局字段合成单一监听以保持向后兼容。</p>
 *
 * <p>启停由 bridge-core 的生命周期组件驱动。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@Component
public class NettyTcpServer implements AccessServer {

    private static final Logger log = LoggerFactory.getLogger(NettyTcpServer.class);

    private final ChannelManager channelManager;
    private final UplinkFrameListener uplinkFrameListener;
    private final NettyProperties nettyProperties;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private final List<Channel> serverChannels = new ArrayList<>();

    public NettyTcpServer(ChannelManager channelManager, UplinkFrameListener uplinkFrameListener,
                          NettyProperties nettyProperties) {
        this.channelManager = channelManager;
        this.uplinkFrameListener = uplinkFrameListener;
        this.nettyProperties = nettyProperties;
    }

    /**
     * 启动全部监听端口并绑定线程组。任一端口绑定失败即整体回退，避免半启动状态。
     *
     * @throws InterruptedException 绑定过程被中断
     */
    public void start() throws InterruptedException {
        List<Endpoint> endpoints = resolveEndpoints(nettyProperties);
        int bossThreads = nettyProperties.getBossThreads();
        int workerThreads = nettyProperties.getWorkerThreads();
        this.bossGroup = new NioEventLoopGroup(bossThreads);
        this.workerGroup = workerThreads > 0 ? new NioEventLoopGroup(workerThreads) : new NioEventLoopGroup();

        try {
            for (Endpoint endpoint : endpoints) {
                Channel serverChannel = new ServerBootstrap()
                        .group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .option(ChannelOption.SO_BACKLOG, 1024)
                        .childOption(ChannelOption.SO_KEEPALIVE, Boolean.TRUE)
                        // 出站写缓冲水位：慢设备写入积压超过高水位后 isWritable() 为假，下行改走离线暂存
                        .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, writeBufferWaterMark())
                        .childHandler(newInitializer(endpoint))
                        .bind(endpoint.host, endpoint.port).sync().channel();
                serverChannels.add(serverChannel);
                log.info("Netty 监听已启动 name={} 监听 {}:{} 协议={} 切帧={}", endpoint.name, endpoint.host,
                        endpoint.port, endpoint.protocolType == null ? "<默认>" : endpoint.protocolType,
                        endpoint.frameMode);
            }
        } catch (InterruptedException e) {
            stop();
            throw e;
        } catch (RuntimeException e) {
            // 端口占用等绑定失败：回退已启动的监听并释放线程组，避免半启动状态与线程泄漏
            stop();
            throw e;
        }
    }

    /**
     * 构建出站写缓冲水位（配置非法时启动失败，避免静默用错误水位）。
     *
     * @return 写缓冲水位
     */
    private WriteBufferWaterMark writeBufferWaterMark() {
        int low = nettyProperties.getWriteBufferLowWaterMark() == null
                ? 32 * 1024 : nettyProperties.getWriteBufferLowWaterMark();
        int high = nettyProperties.getWriteBufferHighWaterMark() == null
                ? 64 * 1024 : nettyProperties.getWriteBufferHighWaterMark();
        if (low <= 0 || high <= 0 || low >= high) {
            throw new IllegalStateException("写缓冲水位非法：low=" + low + " high=" + high
                    + "，要求 0 < low < high（iotbridge.netty.write-buffer-low-water-mark/high-water-mark）");
        }
        return new WriteBufferWaterMark(low, high);
    }

    /**
     * 为单个监听构建通道初始化器：按端口的切帧方式装配帧解码器，并让入站处理器携带该端口协议类型。
     *
     * @param endpoint 已解析的监听端点
     * @return 通道初始化器
     */
    private ChannelInitializer<SocketChannel> newInitializer(Endpoint endpoint) {
        int readerIdleSeconds = nettyProperties.getReaderIdleSeconds() == null
                ? 0 : nettyProperties.getReaderIdleSeconds();
        return new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                if (readerIdleSeconds > 0) {
                    // 读空闲检测：超时未收到上行即主动断开，清理设备断电/NAT 超时留下的半开连接
                    ch.pipeline().addLast(new IdleStateHandler(readerIdleSeconds, 0, 0, TimeUnit.SECONDS));
                }
                ch.pipeline()
                        .addLast(buildFrameDecoder(endpoint))
                        .addLast(new DeviceInboundHandler(channelManager, uplinkFrameListener,
                                endpoint.protocolType));
            }
        };
    }

    /**
     * 依据端点配置的帧模式构建帧解码器。
     *
     * @param endpoint 已解析的监听端点
     * @return 帧解码器 Handler
     */
    private ChannelHandler buildFrameDecoder(Endpoint endpoint) {
        if (endpoint.frameMode == FrameMode.LENGTH_FIELD) {
            return new LengthFieldBasedFrameDecoder(endpoint.maxFrameLength, endpoint.lengthFieldOffset,
                    endpoint.lengthFieldLength, endpoint.lengthAdjustment, endpoint.initialBytesToStrip);
        }
        return new DelimiterBasedFrameDecoder(endpoint.maxFrameLength, Delimiters.lineDelimiter());
    }

    /**
     * 解析生效的监听端点列表：listeners 为空时用全局扁平字段合成单一监听；
     * 每个监听未填项继承全局默认。
     *
     * @param props Netty 配置
     * @return 已合并默认值的端点列表
     */
    static List<Endpoint> resolveEndpoints(NettyProperties props) {
        List<Listener> listeners = props.getListeners();
        List<Endpoint> endpoints = new ArrayList<>();
        if (listeners == null || listeners.isEmpty()) {
            endpoints.add(merge("default", props.getHost(), props.getPort(), null, props.getFrameMode(),
                    props.getMaxFrameLength(), props.getLengthFieldOffset(), props.getLengthFieldLength(),
                    props.getLengthAdjustment(), props.getInitialBytesToStrip(), props));
        } else {
            for (Listener listener : listeners) {
                if (listener.getPort() == null) {
                    throw new IllegalStateException("监听未配置端口 name=" + listener.getName());
                }
                endpoints.add(merge(listener.getName(), listener.getHost(), listener.getPort(),
                        listener.getProtocolType(), listener.getFrameMode(), listener.getMaxFrameLength(),
                        listener.getLengthFieldOffset(), listener.getLengthFieldLength(), listener.getLengthAdjustment(),
                        listener.getInitialBytesToStrip(), props));
            }
        }
        assertNoDuplicateAddress(endpoints);
        return Collections.unmodifiableList(endpoints);
    }

    /**
     * 校验监听地址不重复且端口合法：重复绑定必然失败，提前 fail-fast 给出明确原因。
     *
     * @param endpoints 已解析端点
     */
    private static void assertNoDuplicateAddress(List<Endpoint> endpoints) {
        Set<String> addresses = new HashSet<>();
        for (Endpoint endpoint : endpoints) {
            if (endpoint.port < 1 || endpoint.port > 65535) {
                throw new IllegalStateException("监听端口非法 name=" + endpoint.name + " port=" + endpoint.port);
            }
            String address = endpoint.host + ":" + endpoint.port;
            if (!addresses.add(address)) {
                throw new IllegalStateException("监听地址重复 name=" + endpoint.name + " 地址=" + address);
            }
        }
    }

    /**
     * 将监听配置与全局默认合并，监听项为空时回退全局值。
     *
     * <p>{@link FrameMode#LENGTH_FIELD} 模式下，长度字段三参数（offset/length/adjustment）为协议专属值、
     * 不设通用默认：配置缺失即启动失败（fail-fast），避免用错误参数静默切错帧。</p>
     */
    private static Endpoint merge(String name, String host, Integer port, String protocolType, FrameMode frameMode,
                                  Integer maxFrameLength, Integer lengthFieldOffset, Integer lengthFieldLength,
                                  Integer lengthAdjustment, Integer initialBytesToStrip, NettyProperties props) {
        Endpoint endpoint = new Endpoint();
        endpoint.name = (name == null || name.isEmpty()) ? "port-" + port : name;
        endpoint.host = host != null ? host : props.getHost();
        endpoint.port = port;
        endpoint.protocolType = (protocolType == null || protocolType.isEmpty()) ? null : protocolType;
        endpoint.frameMode = frameMode != null ? frameMode : props.getFrameMode();
        endpoint.maxFrameLength = maxFrameLength != null ? maxFrameLength : props.getMaxFrameLength();
        endpoint.lengthFieldOffset = lengthFieldOffset != null ? lengthFieldOffset : props.getLengthFieldOffset();
        endpoint.lengthFieldLength = lengthFieldLength != null ? lengthFieldLength : props.getLengthFieldLength();
        endpoint.lengthAdjustment = lengthAdjustment != null ? lengthAdjustment : props.getLengthAdjustment();
        endpoint.initialBytesToStrip = initialBytesToStrip != null ? initialBytesToStrip
                : props.getInitialBytesToStrip();
        if (endpoint.frameMode == FrameMode.LENGTH_FIELD
                && (endpoint.lengthFieldOffset == null || endpoint.lengthFieldLength == null
                || endpoint.lengthAdjustment == null)) {
            throw new IllegalStateException(
                    "LENGTH_FIELD 切帧必须完整配置 length-field-offset/length-field-length/length-adjustment"
                            + "（监听项或全局 iotbridge.netty），监听 name=" + endpoint.name);
        }
        return endpoint;
    }

    /**
     * 关闭全部监听与线程组，释放资源。
     */
    public void stop() {
        for (Channel serverChannel : serverChannels) {
            serverChannel.close().syncUninterruptibly();
        }
        serverChannels.clear();
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        log.info("Netty TCP 服务端已关闭");
    }

    /**
     * 已解析、合并全局默认后的监听端点（内部值对象）。
     */
    static final class Endpoint {

        private String name;
        private String host;
        private int port;
        private String protocolType;
        private FrameMode frameMode;
        private int maxFrameLength;
        private Integer lengthFieldOffset;
        private Integer lengthFieldLength;
        private Integer lengthAdjustment;
        private Integer initialBytesToStrip;

        String getName() {
            return name;
        }

        int getPort() {
            return port;
        }

        FrameMode getFrameMode() {
            return frameMode;
        }

        String getHost() {
            return host;
        }

        String getProtocolType() {
            return protocolType;
        }

        int getMaxFrameLength() {
            return maxFrameLength;
        }

        Integer getLengthFieldOffset() {
            return lengthFieldOffset;
        }

        Integer getLengthFieldLength() {
            return lengthFieldLength;
        }

        Integer getLengthAdjustment() {
            return lengthAdjustment;
        }

        Integer getInitialBytesToStrip() {
            return initialBytesToStrip;
        }
    }
}
