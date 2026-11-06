package com.iotbridge.demo;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.mqtt.MqttConnAckMessage;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.codec.mqtt.MqttDecoder;
import io.netty.handler.codec.mqtt.MqttEncoder;
import io.netty.handler.codec.mqtt.MqttMessage;
import io.netty.handler.codec.mqtt.MqttMessageBuilders;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.handler.codec.mqtt.MqttVersion;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * MQTT 3.1.1 模拟设备客户端（可运行示例）。
 *
 * <p>连接网关内置 MQTT 服务端（默认 1883），流程：CONNECT -> 订阅下行主题
 * {@code iotbridge/downlink/<clientId>} -> 发布一条 JSON 上行到 {@code iotbridge/uplink/<clientId>}
 * -> 定时 PINGREQ 保活并持续打印网关下发的 PUBLISH。clientId 即网关侧设备号，可直接用于 REST 在线查询与指令下发：
 * {@code curl -XPOST localhost:8080/api/v1/devices/<clientId>/commands -H 'Content-Type: application/json'
 * -d '{"protocolType":"mqtt","command":"setInterval","params":{"value":30}}'}。</p>
 *
 * <p>直接以 {@code main} 方法在 IDE 中运行。</p>
 *
 * @author iotbridge
 * @date 2026-11-06
 */
public final class MqttDemoClient {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 1883;
    private static final String CLIENT_ID = "dev-mqtt";
    private static final String UPLINK_TOPIC_PREFIX = "iotbridge/uplink";
    private static final String DOWNLINK_TOPIC_PREFIX = "iotbridge/downlink";

    /**
     * 客户端 keepalive（秒）；网关按 1.5 倍做读空闲检测。
     */
    private static final int KEEP_ALIVE_SECONDS = 60;

    private MqttDemoClient() {
        // 工具类不允许实例化
    }

    /**
     * 客户端入口。
     *
     * @param args 启动参数（均可省略）：{@code args[0]}=clientId、{@code args[1]}=主机、{@code args[2]}=端口、
     *             {@code args[3]}=用户名、{@code args[4]}=密码（网关未开启鉴权时留空）
     * @throws InterruptedException 连接或等待被关闭
     */
    public static void main(String[] args) throws InterruptedException {
        String clientId = args.length >= 1 ? args[0] : CLIENT_ID;
        String host = args.length >= 2 ? args[1] : HOST;
        int port = args.length >= 3 ? Integer.parseInt(args[2]) : PORT;
        String username = args.length >= 4 ? args[3] : "";
        String password = args.length >= 5 ? args[4] : "";

        String downlinkTopic = DOWNLINK_TOPIC_PREFIX + "/" + clientId;
        String uplinkTopic = UPLINK_TOPIC_PREFIX + "/" + clientId;
        String uplinkPayload = "{\"deviceId\":\"" + clientId + "\",\"temperature\":26.5,\"humidity\":60}";

        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new MqttDecoder())
                                    .addLast(MqttEncoder.INSTANCE)
                                    .addLast(new DemoMqttHandler(clientId, username, password,
                                            downlinkTopic, uplinkTopic, uplinkPayload));
                        }
                    });

            Channel channel = bootstrap.connect(host, port).sync().channel();
            System.out.println("[mqtt-device] 已连接网关 " + host + ":" + port + " clientId=" + clientId);
            channel.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    /**
     * MQTT 客户端处理器：CONNECT、订阅、上行发布、保活与下行打印。
     */
    private static final class DemoMqttHandler extends SimpleChannelInboundHandler<MqttMessage> {

        private final String clientId;
        private final String username;
        private final String password;
        private final String downlinkTopic;
        private final String uplinkTopic;
        private final String uplinkPayload;

        private DemoMqttHandler(String clientId, String username, String password,
                                String downlinkTopic, String uplinkTopic, String uplinkPayload) {
            this.clientId = clientId;
            this.username = username;
            this.password = password;
            this.downlinkTopic = downlinkTopic;
            this.uplinkTopic = uplinkTopic;
            this.uplinkPayload = uplinkPayload;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            MqttMessageBuilders.ConnectBuilder connect = MqttMessageBuilders.connect()
                    .protocolVersion(MqttVersion.MQTT_3_1_1)
                    .clientId(clientId)
                    .cleanSession(true)
                    .keepAlive(KEEP_ALIVE_SECONDS);
            if (!username.isEmpty()) {
                connect.hasUser(true).username(username);
                connect.hasPassword(true).password(password);
            }
            ctx.writeAndFlush(connect.build());
            // 与 keepalive 周期的一半发送 PINGREQ，避免被网关判为空闲
            ctx.executor().scheduleAtFixedRate(() -> ctx.writeAndFlush(MqttMessage.PINGREQ),
                    KEEP_ALIVE_SECONDS / 2, KEEP_ALIVE_SECONDS / 2, TimeUnit.SECONDS);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, MqttMessage msg) {
            switch (msg.fixedHeader().messageType()) {
                case CONNACK -> onConnAck(ctx, (MqttConnAckMessage) msg);
                case SUBACK -> System.out.println("[mqtt-device] 下行主题订阅成功: " + downlinkTopic);
                case PUBLISH -> onPublish((MqttPublishMessage) msg);
                case PINGRESP -> {
                    // 保活响应无需打印，避免日志刷屏
                }
                default -> System.out.println("[mqtt-device] 收到报文: " + msg.fixedHeader().messageType());
            }
        }

        private void onConnAck(ChannelHandlerContext ctx, MqttConnAckMessage connAck) {
            MqttConnectReturnCode code = connAck.variableHeader().connectReturnCode();
            if (code != MqttConnectReturnCode.CONNECTION_ACCEPTED) {
                System.err.println("[mqtt-device] 连接被拒绝: " + code);
                ctx.close();
                return;
            }
            System.out.println("[mqtt-device] CONNECT 成功 clientId=" + clientId);
            ctx.writeAndFlush(MqttMessageBuilders.subscribe()
                    .messageId(1)
                    .addSubscription(MqttQoS.AT_MOST_ONCE, downlinkTopic)
                    .build());
            ByteBuf payload = Unpooled.copiedBuffer(uplinkPayload, StandardCharsets.UTF_8);
            ctx.writeAndFlush(MqttMessageBuilders.publish()
                    .topicName(uplinkTopic)
                    .qos(MqttQoS.AT_MOST_ONCE)
                    .payload(payload)
                    .build());
            System.out.println("[mqtt-device] 已发送上行 " + uplinkTopic + ": " + uplinkPayload);
            System.out.println("[mqtt-device] 等待下行指令……（对网关发起 curl 下发即可看到 PUBLISH）");
        }

        private void onPublish(MqttPublishMessage publish) {
            ByteBuf payload = publish.payload();
            String text = payload == null ? "" : payload.toString(StandardCharsets.UTF_8);
            System.out.println("[mqtt-device] 收到下行 " + publish.variableHeader().topicName() + ": " + text);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("[mqtt-device] 通道异常: " + cause.getMessage());
            ctx.close();
        }
    }
}
