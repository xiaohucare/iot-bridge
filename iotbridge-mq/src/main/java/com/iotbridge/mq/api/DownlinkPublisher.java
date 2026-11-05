package com.iotbridge.mq.api;

import com.iotbridge.spi.model.DownlinkCommand;

/**
 * 下行指令发布者抽象。
 *
 * <p>供 admin 层调用，将业务平台指令投递至消息中间件（示例为 RabbitMQ）。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
public interface DownlinkPublisher {

    /**
     * 发布一条下行指令。
     *
     * @param command 下行指令，不可为 {@code null}
     */
    void publish(DownlinkCommand command);
}
