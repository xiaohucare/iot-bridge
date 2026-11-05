package com.iotbridge.mq.rabbit;

import com.iotbridge.common.constant.IotbridgeConstants;
import com.iotbridge.spi.handler.DownlinkDispatchHandler;
import com.iotbridge.spi.model.DownlinkCommand;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * RabbitMQ 下行指令消费者。
 *
 * <p>消费下行队列并回调 {@link DownlinkDispatchHandler}（由 bridge-core 实现），
 * 借助接口反转避免 mq 模块反向依赖 core。</p>
 *
 * <p><b>手动确认</b>：处理成功（或按既有约定丢弃毒消息）后 ack；处理链路异常时 nack 且不重回队列，
 * 经队列死信参数转入 {@code <下行队列>.dlq}，杜绝毒消息无限重投。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@Component
public class DownlinkCommandConsumer {

    private static final Logger log = LoggerFactory.getLogger(DownlinkCommandConsumer.class);

    private final DownlinkDispatchHandler dispatchHandler;

    public DownlinkCommandConsumer(DownlinkDispatchHandler dispatchHandler) {
        this.dispatchHandler = dispatchHandler;
    }

    /**
     * 监听下行队列，处理单条指令（手动确认）。
     *
     * <p>队列名走 {@code iotbridge.mq.downlink-queue} 配置（与队列声明、下行发布同源），缺省回退
     * {@link IotbridgeConstants#QUEUE_DOWNLINK}；如此每实例可指定独立队列，多实例共存时不至于互抢同一队列。</p>
     *
     * @param command     反序列化后的下行指令
     * @param channel     RabbitMQ 通道（用于手动 ack/nack）
     * @param deliveryTag 投递标签
     * @throws IOException 通道 IO 异常（ack/nack 失败时由容器按连接异常处理）
     */
    @RabbitListener(queues = "${iotbridge.mq.downlink-queue:" + IotbridgeConstants.QUEUE_DOWNLINK + "}",
            ackMode = "MANUAL")
    public void onMessage(DownlinkCommand command, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            if (command == null) {
                // 反序列化异常（毒消息）：不重回队列，转入死信队列排查
                log.warn("收到空/无法反序列化的下行指令，转入死信队列 deliveryTag={}", deliveryTag);
                channel.basicNack(deliveryTag, false, false);
                return;
            }
            if (log.isDebugEnabled()) {
                log.debug("消费下行指令 deviceId={} command={} requestId={}", command.getDeviceId(),
                        command.getCommand(), command.getRequestId());
            }
            // handle 对确定性错误（协议/编码失败、设备离线等）均内部消化，绝不外抛；
            // 正常返回即处理完成，确认消费。
            dispatchHandler.handle(command);
            channel.basicAck(deliveryTag, false);
        } catch (RuntimeException ex) {
            log.error("下行指令处理异常，转入死信队列 requestId={}", command == null ? null : command.getRequestId(), ex);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
