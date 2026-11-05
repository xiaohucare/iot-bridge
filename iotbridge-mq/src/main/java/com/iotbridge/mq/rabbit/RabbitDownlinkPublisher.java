package com.iotbridge.mq.rabbit;

import com.iotbridge.mq.api.DownlinkPublisher;
import com.iotbridge.mq.config.MqProperties;
import com.iotbridge.spi.model.DownlinkCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 基于 RabbitMQ 的下行指令发布者实现。
 *
 * <p>将指令投递到下行交换机，经绑定路由到下行队列，由 {@link DownlinkCommandConsumer} 消费。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@Component
public class RabbitDownlinkPublisher implements DownlinkPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitDownlinkPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final MqProperties mqProperties;

    public RabbitDownlinkPublisher(RabbitTemplate rabbitTemplate, MqProperties mqProperties) {
        this.rabbitTemplate = rabbitTemplate;
        this.mqProperties = mqProperties;
    }

    @Override
    public void publish(DownlinkCommand command) {
        if (command == null) {
            return;
        }
        String exchange = mqProperties.getDownlinkExchange();
        String routingKey = mqProperties.getDownlinkRoutingKey();
        rabbitTemplate.convertAndSend(exchange, routingKey, command);
        if (log.isDebugEnabled()) {
            log.debug("下行指令已发布 exchange={} routingKey={} deviceId={}", exchange, routingKey,
                    command.getDeviceId());
        }
    }
}
