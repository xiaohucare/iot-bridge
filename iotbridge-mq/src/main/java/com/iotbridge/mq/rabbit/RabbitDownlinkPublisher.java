package com.iotbridge.mq.rabbit;

import com.iotbridge.common.exception.BridgeException;
import com.iotbridge.common.exception.ErrorCodeEnum;
import com.iotbridge.mq.api.DownlinkPublisher;
import com.iotbridge.mq.config.MqProperties;
import com.iotbridge.spi.model.DownlinkCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 基于 RabbitMQ 的下行指令发布者实现。
 *
 * <p>将指令投递到下行交换机，经绑定路由到下行队列，由 {@link DownlinkCommandConsumer} 消费。</p>
 *
 * <p><b>发布确认（同步等待）</b>：发布后同步等待 broker confirm（超时见
 * {@code iotbridge.mq.downlink-confirm-timeout-ms}），nack/超时/不可路由退回时抛出业务异常，
 * 使 REST 调用方得到失败响应而非假成功；配合 broker 持久化与消费端手动 ack，
 * 下行链路为「至少一次，不假成功」语义。</p>
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
        String requestId = command.getRequestId() == null ? UUID.randomUUID().toString() : command.getRequestId();
        CorrelationData correlationData = new CorrelationData(requestId);
        rabbitTemplate.convertAndSend(exchange, routingKey, command, correlationData);
        // 同步等待 broker confirm：杜绝「REST 返回成功但消息未落队列」的假成功
        try {
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(mqProperties.getDownlinkConfirmTimeoutMs(), TimeUnit.MILLISECONDS);
            if (confirm == null || !confirm.isAck()) {
                throw new BridgeException(ErrorCodeEnum.SYSTEM_ERROR,
                        "RabbitMQ 未确认下行指令 requestId=" + requestId
                                + (confirm == null ? "" : " reason=" + confirm.getReason()));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BridgeException(ErrorCodeEnum.SYSTEM_ERROR, "等待下行指令确认被中断 requestId=" + requestId, ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new BridgeException(ErrorCodeEnum.SYSTEM_ERROR, "等待下行指令确认失败 requestId=" + requestId, ex);
        }
        if (log.isDebugEnabled()) {
            log.debug("下行指令已发布并确认 exchange={} routingKey={} deviceId={} requestId={}", exchange, routingKey,
                    command.getDeviceId(), requestId);
        }
    }
}
