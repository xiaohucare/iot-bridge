package com.iotbridge.mq.config;

import com.iotbridge.common.constant.IotbridgeConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MQ 相关可配置属性，前缀 {@code iotbridge.mq}。
 *
 * <p>缺省值取自 {@link IotbridgeConstants}，无需配置即可运行示例。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@ConfigurationProperties(prefix = "iotbridge.mq")
public class MqProperties {

    /**
     * 上行 Kafka 主题。
     */
    private String uplinkTopic = IotbridgeConstants.TOPIC_UPLINK;

    /**
     * 下行 RabbitMQ 队列名。
     */
    private String downlinkQueue = IotbridgeConstants.QUEUE_DOWNLINK;

    /**
     * 下行 RabbitMQ 交换机名。
     */
    private String downlinkExchange = IotbridgeConstants.EXCHANGE_DOWNLINK;

    /**
     * 下行 RabbitMQ 路由键。
     */
    private String downlinkRoutingKey = IotbridgeConstants.ROUTING_KEY_DOWNLINK;

    /**
     * 下行发布等待 broker confirm 的超时时间（毫秒）。超时或 nack 时发布端抛出业务异常，
     * 使 REST 调用方得到失败响应而非假成功。
     */
    private long downlinkConfirmTimeoutMs = 5000L;

    /**
     * 下行交换机类型：{@code direct}（单实例，固定路由键）或 {@code consistent-hash}（多实例，按 deviceId 路由）。
     *
     * <p>多实例部署必须使用 {@code consistent-hash}：每个实例声明独立队列（见 {@link #effectiveQueueName()}）
     * 绑定一致哈希交换机，发布端以 deviceId 作为 routing key，同一设备恒定命中同一实例的队列，
     * 避免直连交换机「同 routing key 绑多个队列 = 广播」造成的重复下发与随机错投。</p>
     */
    private String downlinkExchangeType = EXCHANGE_TYPE_DIRECT;

    /**
     * 实例标识（用于一致哈希模式下的独立队列命名），缺省取环境变量 {@code HOSTNAME}，再退化为 {@code local}。
     */
    private String instanceId = "";

    /**
     * 直连交换机类型标识。
     */
    public static final String EXCHANGE_TYPE_DIRECT = "direct";

    /**
     * 一致哈希交换机类型标识。
     */
    public static final String EXCHANGE_TYPE_CONSISTENT_HASH = "consistent-hash";

    /**
     * 是否启用一致哈希路由（多实例）。
     *
     * @return 启用返回 {@code true}
     */
    public boolean isConsistentHash() {
        return EXCHANGE_TYPE_CONSISTENT_HASH.equalsIgnoreCase(downlinkExchangeType);
    }

    /**
     * 生效的实例标识。
     *
     * @return 实例标识
     */
    public String effectiveInstanceId() {
        if (instanceId != null && !instanceId.isBlank()) {
            return instanceId;
        }
        String hostname = System.getenv("HOSTNAME");
        return hostname == null || hostname.isBlank() ? "local" : hostname;
    }

    /**
     * 生效的下行队列名：一致哈希模式下按实例隔离，直连模式沿用配置名。
     *
     * @return 队列名
     */
    public String effectiveQueueName() {
        return isConsistentHash() ? downlinkQueue + "." + effectiveInstanceId() : downlinkQueue;
    }

    /**
     * 生效的发布路由键：一致哈希模式以 deviceId 路由，直连模式用配置的固定键。
     *
     * @param deviceId 设备标识
     * @return 路由键
     */
    public String effectiveRoutingKey(String deviceId) {
        return isConsistentHash() ? deviceId : downlinkRoutingKey;
    }

    public String getDownlinkExchangeType() {
        return downlinkExchangeType;
    }

    public void setDownlinkExchangeType(String downlinkExchangeType) {
        this.downlinkExchangeType = downlinkExchangeType;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getUplinkTopic() {
        return uplinkTopic;
    }

    public void setUplinkTopic(String uplinkTopic) {
        this.uplinkTopic = uplinkTopic;
    }

    public String getDownlinkQueue() {
        return downlinkQueue;
    }

    public void setDownlinkQueue(String downlinkQueue) {
        this.downlinkQueue = downlinkQueue;
    }

    public String getDownlinkExchange() {
        return downlinkExchange;
    }

    public void setDownlinkExchange(String downlinkExchange) {
        this.downlinkExchange = downlinkExchange;
    }

    public String getDownlinkRoutingKey() {
        return downlinkRoutingKey;
    }

    public void setDownlinkRoutingKey(String downlinkRoutingKey) {
        this.downlinkRoutingKey = downlinkRoutingKey;
    }

    public long getDownlinkConfirmTimeoutMs() {
        return downlinkConfirmTimeoutMs;
    }

    public void setDownlinkConfirmTimeoutMs(long downlinkConfirmTimeoutMs) {
        this.downlinkConfirmTimeoutMs = downlinkConfirmTimeoutMs;
    }
}
