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
