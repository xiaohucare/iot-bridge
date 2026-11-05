package com.iotbridge.mq.rabbit;

import com.iotbridge.common.constant.IotbridgeConstants;
import com.iotbridge.spi.handler.DownlinkDispatchHandler;
import com.iotbridge.spi.model.DownlinkCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ 下行指令消费者。
 *
 * <p>消费下行队列并回调 {@link DownlinkDispatchHandler}（由 bridge-core 实现），
 * 借助接口反转避免 mq 模块反向依赖 core。</p>
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
     * 监听下行队列，处理单条指令。
     *
     * @param command 反序列化后的下行指令
     */
    @RabbitListener(queues = IotbridgeConstants.QUEUE_DOWNLINK)
    public void onMessage(DownlinkCommand command) {
        if (command == null) {
            log.warn("收到空下行指令，已忽略");
            return;
        }
        if (log.isDebugEnabled()) {
            log.debug("消费下行指令 deviceId={} command={} requestId={}", command.getDeviceId(),
                    command.getCommand(), command.getRequestId());
        }
        dispatchHandler.handle(command);
    }
}
