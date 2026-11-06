package com.iotbridge.access.mqtt.handler;

import com.iotbridge.access.mqtt.config.MqttProperties;
import com.iotbridge.access.mqtt.util.MqttTopicMatcher;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.mqtt.MqttMessageBuilders;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttQoS;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 下行写适配：把 {@code ChannelManager.sendToDevice} 写入的字节包装为 MQTT PUBLISH。
 *
 * <p>位于 {@code MqttEncoder} 之前（出站方向先经过本处理器），因此网关内部统一的下行字节
 * （协议插件编码结果）无需感知 MQTT；本处理器读取通道上的 clientId/下行主题/订阅集合，
 * 未建立会话或设备未订阅对应主题时令写操作失败，从而让上层按「未送达」走离线暂存策略。</p>
 *
 * @author iotbridge
 * @date 2026-11-06
 */
public class MqttDownlinkWriteHandler extends ChannelOutboundHandlerAdapter {

    private final MqttQoS qos;

    public MqttDownlinkWriteHandler(MqttProperties properties) {
        this.qos = properties.getQos() != null && properties.getQos() == 1
                ? MqttQoS.AT_LEAST_ONCE : MqttQoS.AT_MOST_ONCE;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (!(msg instanceof ByteBuf payload)) {
            // 其它 MqttMessage（CONNACK/SUBACK/PINGRESP 等）原样交给编码器
            ctx.write(msg, promise);
            return;
        }
        String clientId = ctx.channel().attr(MqttChannelAttributes.CLIENT_ID).get();
        String topic = ctx.channel().attr(MqttChannelAttributes.DOWNLINK_TOPIC).get();
        if (clientId == null || topic == null) {
            payload.release();
            promise.tryFailure(new IllegalStateException("MQTT 会话尚未建立，无法下发"));
            return;
        }
        Set<String> subscriptions = ctx.channel().attr(MqttChannelAttributes.SUBSCRIPTIONS).get();
        if (!MqttTopicMatcher.matchesAny(subscriptions, topic)) {
            payload.release();
            promise.tryFailure(new IllegalStateException("MQTT 设备未订阅下行主题: " + topic));
            return;
        }
        int messageId = 0;
        if (qos == MqttQoS.AT_LEAST_ONCE) {
            AtomicInteger sequence = ctx.channel().attr(MqttChannelAttributes.MESSAGE_ID).get();
            messageId = sequence.updateAndGet(current -> current >= 65535 ? 1 : current + 1);
        }
        MqttPublishMessage publish = MqttMessageBuilders.publish()
                .topicName(topic)
                .qos(qos)
                .retained(false)
                .messageId(messageId)
                // payload 的所有权转移给 publish 消息，由 MqttEncoder 编码后释放
                .payload(payload)
                .build();
        ctx.write(publish, promise);
    }
}
