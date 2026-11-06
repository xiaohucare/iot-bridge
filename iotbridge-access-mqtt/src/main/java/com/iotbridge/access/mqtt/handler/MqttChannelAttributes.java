package com.iotbridge.access.mqtt.handler;

import io.netty.util.AttributeKey;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MQTT 通道属性键：连接期在 {@code Channel} 上携带 clientId、下行主题与订阅集合，
 * 供下行写适配处理器读取（与入站处理器共享同一通道）。
 *
 * @author iotbridge
 * @date 2026-11-06
 */
public final class MqttChannelAttributes {

    /**
     * MQTT clientId，作为设备标识。
     */
    public static final AttributeKey<String> CLIENT_ID = AttributeKey.valueOf("iotbridge.mqtt.clientId");

    /**
     * 该设备的下行 PUBLISH 主题。
     */
    public static final AttributeKey<String> DOWNLINK_TOPIC = AttributeKey.valueOf("iotbridge.mqtt.downlinkTopic");

    /**
     * 设备已订阅的主题过滤器集合。
     */
    public static final AttributeKey<Set<String>> SUBSCRIPTIONS =
            AttributeKey.valueOf("iotbridge.mqtt.subscriptions");

    /**
     * QoS1 下行 PUBLISH 的报文标识序号。
     */
    public static final AttributeKey<AtomicInteger> MESSAGE_ID = AttributeKey.valueOf("iotbridge.mqtt.messageId");

    private MqttChannelAttributes() {
        // 常量类不允许实例化
    }
}
