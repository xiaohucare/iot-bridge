package com.iotbridge.access.mqtt.server;

import com.iotbridge.access.mqtt.config.MqttProperties;
import com.iotbridge.access.mqtt.handler.MqttConnectionHandler;
import com.iotbridge.access.mqtt.handler.MqttDownlinkWriteHandler;
import com.iotbridge.access.netty.api.AccessServer;
import com.iotbridge.access.netty.api.UplinkFrameListener;
import com.iotbridge.access.netty.session.ChannelManager;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.mqtt.MqttDecoder;
import io.netty.handler.codec.mqtt.MqttEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * MQTT 3.1.1 服务端（轻量 Broker）。
 *
 * <p>仅承担设备接入职责：CONNECT/订阅/发布由 {@link MqttConnectionHandler} 处理，上行交给
 * bridge-core 投递 Kafka，下行复用 {@link ChannelManager} 寻址并通过
 * {@link MqttDownlinkWriteHandler} 包装为 PUBLISH。不实现跨连接消息路由、retained 与 Will。</p>
 *
 * @author iotbridge
 * @date 2026-11-06
 */
@Component
@ConditionalOnProperty(prefix = "iotbridge.mqtt", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MqttServer implements AccessServer {

    private static final Logger log = LoggerFactory.getLogger(MqttServer.class);

    private final MqttProperties properties;
    private final ChannelManager channelManager;
    private final UplinkFrameListener uplinkFrameListener;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public MqttServer(MqttProperties properties, ChannelManager channelManager,
                      UplinkFrameListener uplinkFrameListener) {
        this.properties = properties;
        this.channelManager = channelManager;
        this.uplinkFrameListener = uplinkFrameListener;
    }

    @Override
    public void start() throws InterruptedException {
        validate();
        bossGroup = new NioEventLoopGroup(properties.getBossThreads());
        workerGroup = properties.getWorkerThreads() != null && properties.getWorkerThreads() > 0
                ? new NioEventLoopGroup(properties.getWorkerThreads()) : new NioEventLoopGroup();
        try {
            serverChannel = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, writeBufferWaterMark())
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast("mqttDecoder", new MqttDecoder(properties.getMaxMessageSize()));
                            ch.pipeline().addLast("mqttEncoder", MqttEncoder.INSTANCE);
                            // 位于编码器之前（出站方向），把下行字节包装为 PUBLISH
                            ch.pipeline().addLast("mqttDownlink", new MqttDownlinkWriteHandler(properties));
                            ch.pipeline().addLast("mqttConnection",
                                    new MqttConnectionHandler(channelManager, uplinkFrameListener, properties));
                        }
                    })
                    .bind(properties.getHost(), properties.getPort())
                    .sync()
                    .channel();
            log.info("MQTT 服务端已启动 host={} port={} protocol={}", properties.getHost(), getBoundPort(),
                    properties.getProtocolType());
        } catch (InterruptedException e) {
            stop();
            throw e;
        } catch (RuntimeException e) {
            // 端口占用等绑定失败：释放线程组，避免半启动状态
            stop();
            throw e;
        }
    }

    @Override
    public void stop() {
        Channel channel = serverChannel;
        serverChannel = null;
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
        shutdownGroup(bossGroup);
        bossGroup = null;
        shutdownGroup(workerGroup);
        workerGroup = null;
    }

    /**
     * 实际绑定端口（端口配置为 0 时用于测试取随机端口）。
     *
     * @return 绑定端口；未启动返回 {@code -1}
     */
    public int getBoundPort() {
        Channel channel = serverChannel;
        if (channel == null || !(channel.localAddress() instanceof InetSocketAddress address)) {
            return -1;
        }
        return address.getPort();
    }

    private void shutdownGroup(EventLoopGroup group) {
        if (group != null) {
            group.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    private void validate() {
        Integer port = properties.getPort();
        if (port == null || port < 0 || port > 65535) {
            throw new IllegalStateException("iotbridge.mqtt.port 非法: " + port);
        }
        String host = properties.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("iotbridge.mqtt.host 不能为空");
        }
        String protocolType = properties.getProtocolType();
        if (protocolType == null || protocolType.isBlank()) {
            throw new IllegalStateException("iotbridge.mqtt.protocol-type 不能为空");
        }
        Integer maxMessageSize = properties.getMaxMessageSize();
        if (maxMessageSize == null || maxMessageSize <= 0) {
            throw new IllegalStateException("iotbridge.mqtt.max-message-size 必须大于 0");
        }
        Integer qos = properties.getQos();
        if (qos == null || qos < 0 || qos > 1) {
            throw new IllegalStateException("iotbridge.mqtt.qos 仅支持 0 或 1，当前: " + qos);
        }
    }

    private WriteBufferWaterMark writeBufferWaterMark() {
        Integer low = properties.getWriteBufferLowWaterMark();
        Integer high = properties.getWriteBufferHighWaterMark();
        if (low == null || high == null || low <= 0 || high <= low) {
            throw new IllegalStateException("MQTT 写缓冲水位非法，需满足 0 < low < high");
        }
        return new WriteBufferWaterMark(low, high);
    }
}
