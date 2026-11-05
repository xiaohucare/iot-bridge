package com.iotbridge.mq.api;

import com.iotbridge.spi.model.UplinkMessage;

/**
 * 上行消息生产者抽象。
 *
 * <p>屏蔽底层中间件（示例为 Kafka），bridge-core 仅依赖此接口投递设备上报数据。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
public interface UplinkProducer {

    /**
     * 投递一条上行消息至消息中间件。
     *
     * @param message 上行消息，不可为 {@code null}
     */
    void send(UplinkMessage message);
}
