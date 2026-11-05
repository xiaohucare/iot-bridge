package com.iotbridge.mq.kafka;

import com.iotbridge.common.util.JsonUtil;
import com.iotbridge.mq.api.UplinkProducer;
import com.iotbridge.mq.config.MqProperties;
import com.iotbridge.spi.model.UplinkMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 基于 Kafka 的上行消息生产者实现。
 *
 * <p>以 {@code deviceId} 作为分区 key，保证同一设备消息有序；负载以 JSON 字符串发送。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@Component
public class KafkaUplinkProducer implements UplinkProducer {

    private static final Logger log = LoggerFactory.getLogger(KafkaUplinkProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MqProperties mqProperties;

    public KafkaUplinkProducer(KafkaTemplate<String, String> kafkaTemplate, MqProperties mqProperties) {
        this.kafkaTemplate = kafkaTemplate;
        this.mqProperties = mqProperties;
    }

    @Override
    public void send(UplinkMessage message) {
        if (message == null) {
            return;
        }
        String topic = mqProperties.getUplinkTopic();
        String payload = JsonUtil.toJson(message);
        CompletableFuture<SendResult<String, String>> future =
                kafkaTemplate.send(topic, message.getDeviceId(), payload);
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("上行消息投递失败 topic={} deviceId={}", topic, message.getDeviceId(), ex);
            } else if (log.isDebugEnabled()) {
                log.debug("上行消息投递成功 topic={} deviceId={} offset={}", topic,
                        message.getDeviceId(), result.getRecordMetadata().offset());
            }
        });
    }
}
