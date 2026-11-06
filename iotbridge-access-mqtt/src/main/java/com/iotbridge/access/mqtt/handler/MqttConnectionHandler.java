package com.iotbridge.access.mqtt.handler;

import com.iotbridge.access.mqtt.config.MqttProperties;
import com.iotbridge.access.netty.api.UplinkFrameEvent;
import com.iotbridge.access.netty.api.UplinkFrameListener;
import com.iotbridge.access.netty.session.ChannelManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.mqtt.MqttConnectMessage;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.codec.mqtt.MqttMessage;
import io.netty.handler.codec.mqtt.MqttMessageBuilders;
import io.netty.handler.codec.mqtt.MqttMessageType;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.handler.codec.mqtt.MqttSubscribeMessage;
import io.netty.handler.codec.mqtt.MqttTopicSubscription;
import io.netty.handler.codec.mqtt.MqttUnsubscribeMessage;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MQTT 3.1.1 连接处理器：负责 CONNECT 鉴权与注册、PUBLISH 上行、SUBSCRIBE/UNSUBSCRIBE、
 * PINGREQ 心跳与断开清理。
 *
 * <p>设备以 clientId 作为设备号，PUBLISH 到 {@code iotbridge.mqtt.uplink-topic-prefix} 下的主题即
 * 视为上行数据，载荷原样交给 bridge-core 的 {@link UplinkFrameListener}；会话登记复用
 * {@link ChannelManager}，因此 REST 在线查询与下行路由对 MQTT 与 TCP 设备一致。</p>
 *
 * <p>能力边界：支持 QoS0/QoS1，QoS2 不实现（收到即断开）；不支持 retained 持久化与 Will 消息；
 * 下行仅投递给已订阅对应主题的设备，未订阅按未送达处理（交由离线暂存策略）。</p>
 *
 * @author iotbridge
 * @date 2026-11-06
 */
public class MqttConnectionHandler extends SimpleChannelInboundHandler<MqttMessage> {

    private static final Logger log = LoggerFactory.getLogger(MqttConnectionHandler.class);

    /**
     * clientId 最大长度，防止超长标识占用内存与日志。
     */
    private static final int MAX_CLIENT_ID_LENGTH = 128;

    private final ChannelManager channelManager;
    private final UplinkFrameListener uplinkFrameListener;
    private final MqttProperties properties;
    private final MqttQoS downlinkQos;

    public MqttConnectionHandler(ChannelManager channelManager, UplinkFrameListener uplinkFrameListener,
                                 MqttProperties properties) {
        this.channelManager = channelManager;
        this.uplinkFrameListener = uplinkFrameListener;
        this.properties = properties;
        this.downlinkQos = properties.getQos() != null && properties.getQos() == 1
                ? MqttQoS.AT_LEAST_ONCE : MqttQoS.AT_MOST_ONCE;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, MqttMessage msg) {
        if (msg.decoderResult().isFailure()) {
            log.warn("MQTT 报文解析失败，关闭连接 remote={} cause={}", ctx.channel().remoteAddress(),
                    msg.decoderResult().cause() == null ? "unknown" : msg.decoderResult().cause().toString());
            ctx.close();
            return;
        }
        MqttMessageType type = msg.fixedHeader().messageType();
        switch (type) {
            case CONNECT -> onConnect(ctx, msg);
            case PUBLISH -> onPublish(ctx, msg);
            case SUBSCRIBE -> onSubscribe(ctx, msg);
            case UNSUBSCRIBE -> onUnsubscribe(ctx, msg);
            case PINGREQ -> onPingReq(ctx);
            case DISCONNECT -> ctx.close();
            case PUBACK, PUBREC, PUBREL, PUBCOMP -> {
                // 下行 QoS1 未做重传跟踪，客户端确认无需处理
            }
            default -> log.debug("忽略 MQTT 报文类型 type={} remote={}", type, ctx.channel().remoteAddress());
        }
    }

    /**
     * 处理 CONNECT：校验协议版本、clientId 与凭据，登记会话并回 CONNACK。
     */
    private void onConnect(ChannelHandlerContext ctx, MqttMessage msg) {
        if (!(msg instanceof MqttConnectMessage connect)) {
            reject(ctx, MqttConnectReturnCode.CONNECTION_REFUSED_MALFORMED_PACKET, "CONNECT 报文格式非法");
            return;
        }
        if (ctx.channel().attr(MqttChannelAttributes.CLIENT_ID).get() != null) {
            // 同一连接重复 CONNECT 属协议违规
            log.warn("MQTT 重复 CONNECT，关闭连接 remote={}", ctx.channel().remoteAddress());
            ctx.close();
            return;
        }
        int version = connect.variableHeader().version();
        if (version != 3 && version != 4) {
            reject(ctx, MqttConnectReturnCode.CONNECTION_REFUSED_UNACCEPTABLE_PROTOCOL_VERSION,
                    "仅支持 MQTT 3.1/3.1.1，收到版本=" + version);
            return;
        }
        String clientId = connect.payload().clientIdentifier();
        if (clientId == null || clientId.isEmpty()) {
            reject(ctx, MqttConnectReturnCode.CONNECTION_REFUSED_IDENTIFIER_REJECTED, "clientId 不能为空");
            return;
        }
        if (clientId.length() > MAX_CLIENT_ID_LENGTH) {
            reject(ctx, MqttConnectReturnCode.CONNECTION_REFUSED_IDENTIFIER_REJECTED,
                    "clientId 超长（>" + MAX_CLIENT_ID_LENGTH + "）");
            return;
        }
        if (!authenticate(connect)) {
            reject(ctx, MqttConnectReturnCode.CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD, "用户名或密码错误");
            return;
        }

        String protocolType = properties.getProtocolType();
        ctx.channel().attr(MqttChannelAttributes.CLIENT_ID).set(clientId);
        ctx.channel().attr(MqttChannelAttributes.DOWNLINK_TOPIC).set(downlinkTopic(clientId));
        ctx.channel().attr(MqttChannelAttributes.SUBSCRIPTIONS).set(ConcurrentHashMap.newKeySet());
        ctx.channel().attr(MqttChannelAttributes.MESSAGE_ID).set(new AtomicInteger(0));
        channelManager.register(protocolType, clientId, ctx.channel());

        ctx.writeAndFlush(MqttMessageBuilders.connAck()
                .returnCode(MqttConnectReturnCode.CONNECTION_ACCEPTED)
                .sessionPresent(false)
                .build());
        addIdleHandler(ctx, connect.variableHeader().keepAliveTimeSeconds());
        log.info("MQTT 设备已连接 clientId={} remote={} keepAlive={}s", clientId, ctx.channel().remoteAddress(),
                connect.variableHeader().keepAliveTimeSeconds());
    }

    /**
     * 读取并校验用户名/密码；未配置用户名时允许匿名接入。
     */
    private boolean authenticate(MqttConnectMessage connect) {
        if (!properties.isAuthEnabled()) {
            return true;
        }
        String user = connect.payload().userName();
        byte[] password = connect.payload().passwordInBytes();
        if (user == null || password == null) {
            return false;
        }
        byte[] expectedUser = properties.getUsername().getBytes(StandardCharsets.UTF_8);
        byte[] expectedPassword = properties.getPassword() == null
                ? new byte[0] : properties.getPassword().getBytes(StandardCharsets.UTF_8);
        // 两项都比较且不短路，降低时序侧信道风险
        boolean userOk = MessageDigest.isEqual(user.getBytes(StandardCharsets.UTF_8), expectedUser);
        boolean passwordOk = MessageDigest.isEqual(password, expectedPassword);
        return userOk & passwordOk;
    }

    /**
     * 处理 PUBLISH：仅接受上行主题，按 QoS0/1 回执，符号交给 bridge-core 解码投递。
     */
    private void onPublish(ChannelHandlerContext ctx, MqttMessage msg) {
        String clientId = requireConnected(ctx);
        if (clientId == null) {
            return;
        }
        if (!(msg instanceof MqttPublishMessage publish)) {
            return;
        }
        MqttQoS qos = publish.fixedHeader().qosLevel();
        if (qos == MqttQoS.EXACTLY_ONCE) {
            log.warn("暂不支持 MQTT QoS2，关闭连接 clientId={}", clientId);
            ctx.close();
            return;
        }
        String topic = publish.variableHeader().topicName();
        if (topic == null || topic.isEmpty()) {
            log.warn("MQTT PUBLISH 主题为空，关闭连接 clientId={}", clientId);
            ctx.close();
            return;
        }
        ByteBuf payload = publish.payload();
        byte[] frame = payload == null ? new byte[0] : ByteBufUtil.getBytes(payload);
        if (qos == MqttQoS.AT_LEAST_ONCE) {
            ctx.writeAndFlush(MqttMessageBuilders.pubAck()
                    .packetId(publish.variableHeader().messageId())
                    .build());
        }
        if (frame.length == 0) {
            log.debug("MQTT 空载荷 PUBLISH 已忽略 clientId={} topic={}", clientId, topic);
            return;
        }
        if (!isUplinkTopic(topic)) {
            // 网关不是通用消息总线：非上行主题不转发，仅确认以免设备反复重发
            log.debug("忽略非上行主题 clientId={} topic={}", clientId, topic);
            return;
        }
        UplinkFrameEvent event = new UplinkFrameEvent(ctx.channel(), frame,
                String.valueOf(ctx.channel().remoteAddress()), properties.getProtocolType(), clientId);
        try {
            uplinkFrameListener.onUplinkFrame(event);
        } catch (RuntimeException ex) {
            log.error("MQTT 上行处理异常 clientId={} topic={}", clientId, topic, ex);
        }
    }

    /**
     * 处理 SUBSCRIBE：记录主题过滤器并按请求 QoS 授予（最高 QoS1）。
     */
    private void onSubscribe(ChannelHandlerContext ctx, MqttMessage msg) {
        String clientId = requireConnected(ctx);
        if (clientId == null) {
            return;
        }
        if (!(msg instanceof MqttSubscribeMessage subscribe)) {
            return;
        }
        int packetId = subscribe.variableHeader().messageId();
        List<MqttTopicSubscription> subscriptions = subscribe.payload().topicSubscriptions();
        Set<String> filters = ctx.channel().attr(MqttChannelAttributes.SUBSCRIPTIONS).get();
        var subAck = MqttMessageBuilders.subAck().packetId(packetId);
        for (MqttTopicSubscription subscription : subscriptions) {
            String filter = subscription.topicName();
            if (filter == null || filter.isEmpty()) {
                subAck.addGrantedQos(MqttQoS.FAILURE);
                continue;
            }
            filters.add(filter);
            subAck.addGrantedQos(grantedQos(subscription.qualityOfService()));
        }
        ctx.writeAndFlush(subAck.build());
        log.info("MQTT 订阅 clientId={} filters={}", clientId, filters);
    }

    /**
     * 处理 UNSUBSCRIBE：移除过滤器并回 UNSUBACK。
     */
    private void onUnsubscribe(ChannelHandlerContext ctx, MqttMessage msg) {
        String clientId = requireConnected(ctx);
        if (clientId == null) {
            return;
        }
        if (!(msg instanceof MqttUnsubscribeMessage unsubscribe)) {
            return;
        }
        Set<String> filters = ctx.channel().attr(MqttChannelAttributes.SUBSCRIPTIONS).get();
        unsubscribe.payload().topics().forEach(filters::remove);
        ctx.writeAndFlush(MqttMessageBuilders.unsubAck()
                .packetId(unsubscribe.variableHeader().messageId())
                .build());
        log.info("MQTT 取消订阅 clientId={} filters={}", clientId, filters);
    }

    private void onPingReq(ChannelHandlerContext ctx) {
        if (requireConnected(ctx) == null) {
            return;
        }
        ctx.writeAndFlush(MqttMessage.PINGRESP);
    }

    /**
     * 已连接校验：未完成 CONNECT 的报文一律视为协议违规并断开。
     */
    private String requireConnected(ChannelHandlerContext ctx) {
        String clientId = ctx.channel().attr(MqttChannelAttributes.CLIENT_ID).get();
        if (clientId == null) {
            log.warn("MQTT 未完成 CONNECT 即发送报文，关闭连接 remote={}", ctx.channel().remoteAddress());
            ctx.close();
        }
        return clientId;
    }

    /**
     * 上行主题判断：前缀为空时接受全部主题，否则要求等于前缀或位于前缀之下。
     */
    private boolean isUplinkTopic(String topic) {
        String prefix = properties.getUplinkTopicPrefix() == null ? "" : properties.getUplinkTopicPrefix().trim();
        if (prefix.isEmpty()) {
            return true;
        }
        String normalized = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        return topic.equals(normalized) || topic.startsWith(normalized + "/");
    }

    private String downlinkTopic(String clientId) {
        String prefix = properties.getDownlinkTopicPrefix() == null
                ? "" : properties.getDownlinkTopicPrefix().trim();
        if (prefix.isEmpty()) {
            return clientId;
        }
        return prefix.endsWith("/") ? prefix + clientId : prefix + "/" + clientId;
    }

    private MqttQoS grantedQos(MqttQoS requested) {
        if (requested == MqttQoS.FAILURE) {
            return MqttQoS.FAILURE;
        }
        return requested == MqttQoS.AT_MOST_ONCE ? MqttQoS.AT_MOST_ONCE : downlinkQos == MqttQoS.AT_MOST_ONCE
                ? MqttQoS.AT_MOST_ONCE : MqttQoS.AT_LEAST_ONCE;
    }

    /**
     * 依 keepalive 装配读空闲检测：非 0 按 1.5 倍，0 时用配置兜底。
     */
    private void addIdleHandler(ChannelHandlerContext ctx, int keepAliveSeconds) {
        long idleSeconds = keepAliveSeconds > 0
                ? (long) Math.ceil(keepAliveSeconds * 1.5) : properties.getIdleSeconds();
        if (idleSeconds > 0) {
            ctx.pipeline().addFirst("mqtt-idle", new IdleStateHandler(idleSeconds, 0, 0, TimeUnit.SECONDS));
        }
    }

    private void reject(ChannelHandlerContext ctx, MqttConnectReturnCode code, String reason) {
        log.warn("MQTT 拒绝连接 remote={} code={} reason={}", ctx.channel().remoteAddress(), code, reason);
        ctx.writeAndFlush(MqttMessageBuilders.connAck().returnCode(code).sessionPresent(false).build())
                .addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        channelManager.unbindByChannel(ctx.channel());
        log.info("MQTT 设备连接断开 remote={}", ctx.channel().remoteAddress());
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            log.warn("MQTT 设备空闲超时，关闭连接 clientId={} state={}",
                    ctx.channel().attr(MqttChannelAttributes.CLIENT_ID).get(),
                    ((IdleStateEvent) evt).state());
            ctx.close();
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("MQTT 通道异常，关闭连接 remote={}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}
