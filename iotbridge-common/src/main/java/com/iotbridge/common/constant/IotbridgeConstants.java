package com.iotbridge.common.constant;

/**
 * 网关全局常量。
 *
 * <p>集中维护 MQ 主题/队列名、协议分隔符等跨模块共享的字符串常量，避免散落硬编码。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
public final class IotbridgeConstants {

    /**
     * 上行 Kafka 主题：设备上报数据经协议解析后投递至此。
     */
    public static final String TOPIC_UPLINK = "iotbridge.uplink";

    /**
     * 下行 RabbitMQ 队列：业务平台指令投递至此，由网关消费后下发设备。
     */
    public static final String QUEUE_DOWNLINK = "iotbridge.downlink";

    /**
     * 下行 RabbitMQ 交换机。
     */
    public static final String EXCHANGE_DOWNLINK = "iotbridge.downlink.exchange";

    /**
     * 下行 RabbitMQ 路由键。
     */
    public static final String ROUTING_KEY_DOWNLINK = "downlink";

    /**
     * 行协议帧分隔符（LF）。
     */
    public static final String FRAME_DELIMITER = "\n";

    private IotbridgeConstants() {
        // 工具类不允许实例化
    }
}
