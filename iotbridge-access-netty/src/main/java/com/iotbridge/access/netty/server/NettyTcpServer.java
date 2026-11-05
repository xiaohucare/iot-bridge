package com.iotbridge.access.netty.server;

import com.iotbridge.access.netty.api.UplinkFrameListener;
import com.iotbridge.access.netty.config.NettyProperties;
import com.iotbridge.access.netty.handler.DeviceInboundHandler;
import com.iotbridge.access.netty.session.ChannelManager;
import com.iotbridge.access.netty.config.NettyProperties.FrameMode;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Netty TCP 长连接服务端。
 *
 * <p>负责引导 boss/worker 线程组与通道流水线：按行分隔聚合帧，UTF-8 编解码后交由
 * {@link DeviceInboundHandler} 处理。启停由 bridge-core 的生命周期组件驱动。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@Component
public class NettyTcpServer {

    private static final Logger log = LoggerFactory.getLogger(NettyTcpServer.class);

    private final ChannelManager channelManager;
    private final UplinkFrameListener uplinkFrameListener;
    private final NettyProperties nettyProperties;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NettyTcpServer(ChannelManager channelManager, UplinkFrameListener uplinkFrameListener,
                          NettyProperties nettyProperties) {
        this.channelManager = channelManager;
        this.uplinkFrameListener = uplinkFrameListener;
        this.nettyProperties = nettyProperties;
    }

    /**
     * 启动 TCP 服务端并绑定监听端口。
     *
     * @throws InterruptedException 绑定过程被中断
     */
    public void start() throws InterruptedException {
        int bossThreads = nettyProperties.getBossThreads();
        int workerThreads = nettyProperties.getWorkerThreads();
        this.bossGroup = new NioEventLoopGroup(bossThreads);
        this.workerGroup = workerThreads > 0 ? new NioEventLoopGroup(workerThreads) : new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.SO_KEEPALIVE, Boolean.TRUE)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(buildFrameDecoder())
                                .addLast(new DeviceInboundHandler(channelManager, uplinkFrameListener));
                    }
                });

        this.serverChannel = bootstrap.bind(nettyProperties.getHost(), nettyProperties.getPort()).sync()
                .channel();
        log.info("Netty TCP 服务端已启动，监听 {}:{}", nettyProperties.getHost(), nettyProperties.getPort());
    }

    /**
     * 依据配置的帧模式构建帧解码器。
     *
     * @return 帧解码器 Handler
     */
    private ChannelHandler buildFrameDecoder() {
        if (nettyProperties.getFrameMode() == FrameMode.LENGTH_FIELD) {
            return new LengthFieldBasedFrameDecoder(
                    nettyProperties.getMaxFrameLength(),
                    nettyProperties.getLengthFieldOffset(),
                    nettyProperties.getLengthFieldLength(),
                    nettyProperties.getLengthAdjustment(),
                    nettyProperties.getInitialBytesToStrip());
        }
        return new DelimiterBasedFrameDecoder(
                nettyProperties.getMaxFrameLength(), Delimiters.lineDelimiter());
    }

    /**
     * 关闭服务端，释放线程组资源。
     */
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        log.info("Netty TCP 服务端已关闭");
    }
}
