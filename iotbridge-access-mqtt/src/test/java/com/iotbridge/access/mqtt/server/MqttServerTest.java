package com.iotbridge.access.mqtt.server;

import com.iotbridge.access.mqtt.config.MqttProperties;
import com.iotbridge.access.netty.api.UplinkFrameEvent;
import com.iotbridge.access.netty.session.ChannelManager;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.mqtt.MqttConnAckMessage;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.codec.mqtt.MqttDecoder;
import io.netty.handler.codec.mqtt.MqttEncoder;
import io.netty.handler.codec.mqtt.MqttMessage;
import io.netty.handler.codec.mqtt.MqttMessageBuilders;
import io.netty.handler.codec.mqtt.MqttMessageType;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.handler.codec.mqtt.MqttVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link MqttServer} 端到端测试：CONNECT 注册、PUBLISH 上行、SUBSCRIBE 后下行转 PUBLISH、鉴权拒绝。
 *
 * <p>使用 Netty 自带的 MQTT 编解码器作为测试客户端，服务端监听随机端口。</p>
 *
 * @author iotbridge
 * @date 2026-11-06
 */
class MqttServerTest {

    private static final String DEVICE_ID = "dev-mqtt";

    private MqttServer server;
    private EventLoopGroup clientGroup;

    @AfterEach
    void tearDown() {
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 3, TimeUnit.SECONDS).syncUninterruptibly();
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void connectPublishAndDownlinkRoundTrip() throws Exception {
        MqttProperties properties = properties();
        ChannelManager channelManager = new ChannelManager();
        BlockingQueue<UplinkFrameEvent> uplinkEvents = new LinkedBlockingQueue<>();

        server = new MqttServer(properties, channelManager, uplinkEvents::add);
        server.start();

        BlockingQueue<Incoming> incoming = new LinkedBlockingQueue<>();
        Channel client = connect(server.getBoundPort(), incoming);

        client.writeAndFlush(MqttMessageBuilders.connect()
                .protocolVersion(MqttVersion.MQTT_3_1_1)
                .clientId(DEVICE_ID)
                .cleanSession(true)
                .keepAlive(60)
                .build()).sync();
        Incoming connAck = await(incoming, MqttMessageType.CONNACK);
        assertEquals(MqttConnectReturnCode.CONNECTION_ACCEPTED, connAck.returnCode());
        assertTrue(channelManager.isOnline(properties.getProtocolType(), DEVICE_ID));

        client.writeAndFlush(MqttMessageBuilders.subscribe()
                .messageId(10)
                .addSubscription(MqttQoS.AT_MOST_ONCE, "iotbridge/downlink/" + DEVICE_ID)
                .build()).sync();
        await(incoming, MqttMessageType.SUBACK);

        String uplinkJson = "{\"temperature\":26.5,\"humidity\":60}";
        client.writeAndFlush(MqttMessageBuilders.publish()
                .topicName("iotbridge/uplink/" + DEVICE_ID)
                .qos(MqttQoS.AT_MOST_ONCE)
                .payload(Unpooled.copiedBuffer(uplinkJson, StandardCharsets.UTF_8))
                .build()).sync();

        UplinkFrameEvent event = uplinkEvents.poll(3, TimeUnit.SECONDS);
        assertNotNull(event, "服务端未收到上行帧");
        assertEquals(DEVICE_ID, event.getDeviceId());
        assertEquals(properties.getProtocolType(), event.getProtocolType());
        assertEquals(uplinkJson, new String(event.getFrame(), StandardCharsets.UTF_8));

        byte[] downlink = "{\"command\":\"setInterval\"}".getBytes(StandardCharsets.UTF_8);
        assertTrue(channelManager.sendToDevice(properties.getProtocolType(), DEVICE_ID, downlink),
                "下行写入应成功");
        Incoming publish = await(incoming, MqttMessageType.PUBLISH);
        assertEquals("iotbridge/downlink/" + DEVICE_ID, publish.topic());
        assertArrayEquals(downlink, publish.payload());
    }

    @Test
    void wrongPasswordIsRejectedWithConnAck() throws Exception {
        MqttProperties properties = properties();
        properties.setUsername("device");
        properties.setPassword("secret");

        server = new MqttServer(properties, new ChannelManager(), event -> { });
        server.start();

        BlockingQueue<Incoming> incoming = new LinkedBlockingQueue<>();
        Channel client = connect(server.getBoundPort(), incoming);
        client.writeAndFlush(MqttMessageBuilders.connect()
                .protocolVersion(MqttVersion.MQTT_3_1_1)
                .clientId(DEVICE_ID)
                .cleanSession(true)
                .hasUser(true)
                .username("device")
                .hasPassword(true)
                .password("wrong")
                .build()).sync();

        Incoming connAck = await(incoming, MqttMessageType.CONNACK);
        assertEquals(MqttConnectReturnCode.CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD, connAck.returnCode());
    }

    @Test
    void invalidQosFailsFastBeforeBinding() {
        MqttProperties properties = properties();
        properties.setQos(2);

        server = new MqttServer(properties, new ChannelManager(), event -> { });

        assertThrows(IllegalStateException.class, server::start);
    }

    private MqttProperties properties() {
        MqttProperties properties = new MqttProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(0);
        return properties;
    }

    private Channel connect(int port, BlockingQueue<Incoming> incoming) throws InterruptedException {
        clientGroup = new NioEventLoopGroup(1);
        Bootstrap bootstrap = new Bootstrap()
                .group(clientGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new MqttDecoder());
                        ch.pipeline().addLast(MqttEncoder.INSTANCE);
                        ch.pipeline().addLast(new io.netty.channel.SimpleChannelInboundHandler<MqttMessage>() {
                            @Override
                            protected void channelRead0(io.netty.channel.ChannelHandlerContext ctx,
                                                        MqttMessage msg) {
                                MqttConnectReturnCode returnCode = msg instanceof MqttConnAckMessage ack
                                        ? ack.variableHeader().connectReturnCode() : null;
                                String topic = null;
                                byte[] payload = null;
                                if (msg instanceof MqttPublishMessage publish) {
                                    topic = publish.variableHeader().topicName();
                                    payload = ByteBufUtil.getBytes(publish.payload());
                                }
                                incoming.add(new Incoming(msg.fixedHeader().messageType(),
                                        returnCode, topic, payload));
                            }
                        });
                    }
                });
        return bootstrap.connect("127.0.0.1", port).sync().channel();
    }

    private Incoming await(BlockingQueue<Incoming> incoming, MqttMessageType type) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(3);
        while (System.currentTimeMillis() < deadline) {
            Incoming message = incoming.poll(100, TimeUnit.MILLISECONDS);
            if (message != null && message.type() == type) {
                return message;
            }
        }
        fail("未在 3s 内收到报文 " + type);
        return null;
    }

    /**
     * 客户端收到的报文快照（SimpleChannelInboundHandler 会自动释放原消息，不能跨线程持有）。
     */
    private record Incoming(MqttMessageType type, MqttConnectReturnCode returnCode, String topic, byte[] payload) {
    }
}
